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
      apk add --no-cache curl git yq trivy
      cat
    tty: true
"""
            }
        }

        environment {
            // Среда
            GIT_CREDENTIALS_ID = 'jenkins_1'
            BRANCH_NAME = env.BRANCH_NAME ?: 'developer'

            // Конфигурация из файла
            CONFIG_FILE = "${WORKSPACE}/app/.ci-config.yaml"
            APP_NAME = ''
            FULL_IMAGE_NAME = ''
            REPO_URL = 'https://github.com/arch-hcra/st31.git'
            TARGET_PATH = 'app-infra/overlays/dev'
            IMAGE_TAG = ""

            // Docker
            DOCKER_REGISTRY = 'docker.io'
            ARTIFACT_DIR = "${WORKSPACE}/artifacts"
        }

        stages {
            stage('Prepare') {
                steps {
                    container('jnlp') {
                        script {
                            checkout scm

                            if (!fileExists(env.CONFIG_FILE)) {
                                error("Config file ${env.CONFIG_FILE} not found!")
                            }

                            def cfg = readYaml(file: env.CONFIG_FILE)

                            if (!cfg || !cfg.appName) {
                                error("Invalid config: 'appName' is required!")
                            }

                            env.APP_NAME = cfg.appName
                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "${env.DOCKER_REGISTRY}/archcra/${env.APP_NAME}"
                            env.REPO_URL = cfg.infraRepoUrl ?: env.REPO_URL
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: env.TARGET_PATH
                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' : "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"

                            mkdir env.ARTIFACT_DIR
                            echo "=== CONFIG LOADED ==="
                            echo "Image: ${env.FULL_IMAGE_NAME}"
                            echo "Tag: ${env.IMAGE_TAG}"
                        }
                    }
                }
            }

            stage('Build & Test') {
                steps {
                    container('python') {
                        script {
                            sh '''
                                python3 -m venv venv
                                . venv/bin/activate
                                pip install --default-timeout=120 -r app/requirements.txt
                                pytest app/test/test_app.py
                            '''
                        }
                    }
                }
            }

            stage('Security Scan') {
                when {
                    not { env.BRANCH_NAME == 'main' }
                }
                steps {
                    container('tools') {
                        script {
                            sh '''
                                trivy image --exit-code 1 --severity CRITICAL ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                            '''
                        }
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    container('dind') {
                        script {
                            sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -f app/Dockerfile app"
                            sh "docker save ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} > ${env.ARTIFACT_DIR}/${env.IMAGE_TAG}.tar"
                        }
                    }
                }
            }

            stage('Push Docker Image') {
                steps {
                    container('dind') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: 'docker_token_1',
                                usernameVariable: 'DOCKER_USER',
                                passwordVariable: 'DOCKER_PASS'
                            )]) {
                                sh """
                                    echo \${DOCKER_PASS} | docker login -u \${DOCKER_USER} --password-stdin
                                    docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                                """

                                if (env.BRANCH_NAME == 'main') {
                                    sh """
                                        docker tag ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} ${env.FULL_IMAGE_NAME}:latest
                                        docker push ${env.FULL_IMAGE_NAME}:latest
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Build Artifacts') {
                steps {
                    script {
                        stash includes: '**/*.tar', excludes: '', name: 'docker-image'
                    }
                }
            }

            stage('Update Manifests') {
                when {
                    expression { env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'developer' }
                }
                steps {
                    container('tools') {
                        script {
                            withCredentials([string(
                                credentialsId: 'jenkins_1',
                                variable: 'GIT_TOKEN'
                            )]) {
                                sh """
                                    git clone https://${GIT_TOKEN}@github.com/arch-hcra/st31.git /tmp/infra-repo
                                    cd /tmp/infra-repo
                                    git checkout ${env.BRANCH_NAME}

                                    yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i

                                    git config --global user.email "jenkins@ci.local"
                                    git config --global user.name "Jenkins CI"

                                    git add ${env.TARGET_PATH}/kustomization.yaml
                                    git commit -m "chore: update image tag to ${env.IMAGE_TAG} [skip ci]"
                                    git push https://${GIT_TOKEN}@github.com/arch-hcra/st31.git HEAD:${env.BRANCH_NAME}
                                """
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                unstash 'docker-image'
                archiveArtifacts artifacts: '**/*.tar', fingerprint: true
            }
        }
    }
}
