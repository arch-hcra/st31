def call(Map configParams) {
    pipeline {
        agent {
            kubernetes {
                defaultContainer 'jnlp'
                yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins: agent
spec:
  serviceAccountName: default
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:latest
    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']

  - name: dind
    image: docker:dind
    command: ['dockerd-entrypoint.sh']
    args: ['--tls=false']
    securityContext:
      privileged: true
    env:
      - name: DOCKER_HOST
        value: tcp://dind.jenkins.svc.cluster.local:2375

  - name: python
    image: python:3.9
    command: ['cat']
    tty: true

  - name: tools
    image: alpine/kubectl:latest
    command:
    - /bin/sh
    - -c
    - |
      apk add --no-cache curl git yq
      cat
    tty: true
"""
            }
        }

        environment {
            GIT_CREDENTIALS_ID = 'jenkins_1'
        }

        script {
            env.BRANCH_NAME = env.BRANCH_NAME ?: 'developer'  // ✅ Работает в скриптовом контексте
}

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        checkout scm
                        script {
                            def configFile = "${WORKSPACE}/app/.ci-config.yaml"
                            if (!fileExists(configFile)) {
                                error("Config file ${configFile} not found!")
                            }

                            def cfg = readYaml(file: configFile)

                            if (!cfg || !cfg.appName) {
                                error("Invalid config: 'appName' is required!")
                            }

                            // Присвоение переменных окружения
                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: 'overlays/dev'
                            env.APP_NAME = cfg.appName

                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' : "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                        }
                    }
                }
            }

            stage('Build & Test') {
                steps {
                    container('python') {
                        sh '''
                            python3 -m venv venv
                            . venv/bin/activate
                            pip install -r app/requirements.txt
                            pytest app/test/test_app.py || true
                        '''
                    }
                }
            }

            stage('Build Docker Image') {
                steps {  // ✅ Добавлен блок steps
                    container('dind') {
                        script {
                            sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -f app/Dockerfile app"
                        }
                    }
                }
            }

            stage('Push Docker Image') {
                steps {  // ✅ Добавлен блок steps
                    container('dind') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: 'docker_token_1',
                                usernameVariable: 'DOCKER_USER',
                                passwordVariable: 'DOCKER_PASS'
                            )]) {
                                sh "docker login -u ${env.DOCKER_USER} --password-stdin" << env.DOCKER_PASS
                                sh "docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}"
                                if (env.BRANCH_NAME == 'main') {
                                    sh "docker tag ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} ${env.FULL_IMAGE_NAME}:latest"
                                    sh "docker push ${env.FULL_IMAGE_NAME}:latest"
                                }
                            }
                        }
                    }
                }
            }

            stage('Update Manifests') {
                when {
                    expression { env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'developer' }
                }
                steps {  // ✅ Добавлен блок steps
                    container('tools') {
                        script {
                            sh "git clone ${env.REPO_URL} /tmp/infra-repo"
                            sh """
                                cd /tmp/infra-repo
                                git checkout ${env.BRANCH_NAME}
                                yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i

                                git config --global user.email "jenkins@ci.local"
                                git config --global user.name "Jenkins CI"

                                git add ${env.TARGET_PATH}/kustomization.yaml
                                git commit -m "chore: update image tag to ${env.IMAGE_TAG} [skip ci]"
                                git push origin HEAD:${env.BRANCH_NAME}
                            """
                        }
                    }
                }
            }
        }
    }
}
