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

        // Блок конфигураций и логинов
        environment {
            GIT_CREDENTIALS_ID = configParams.gitCredentialsId ?: 'jenkins_1'
            DOCKER_CREDENTIALS_ID = configParams.dockerCredentialsId ?: 'docker_token_1'
            TRIVY_IMAGE = configParams.trivyImage ?: 'aquasec/trivy:latest'
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        script {
                            checkout scm

                            env.BRANCH_NAME = env.BRANCH_NAME ?: configParams.defaultBranch ?: 'developer'

                            def configFile = "${WORKSPACE}/app/.ci-config.yaml"
                            if (!fileExists(configFile)) {
                                error("Config file ${configFile} not found!")
                            }

                            def cfg = readYaml(file: configFile)

                            if (!cfg || !cfg.appName) {
                                error("Invalid config: 'appName' is required!")
                            }

                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: configParams.infraRepoUrl ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: configParams.infraRepoTargetPath ?: 'app-infra/overlays/dev'
                            env.APP_NAME = cfg.appName

                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' :
                                "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
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
                            pip install --default-timeout=120 -r app/requirements.txt
                            pytest app/test/test_app.py
                        '''
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    container('dind') {
                        script {
                            sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -f app/Dockerfile app"
                        }
                    }
                }
            }

            stage('Scan for Vulnerabilities') {
                steps {
                    container('tools') {
                        withCredentials([usernamePassword(
                            credentialsId: configParams.trivyCredentialsId ?: 'default',
                            usernameVariable: 'TRIVY_USERNAME',
                            passwordVariable: 'TRIVY_PASSWORD'
                        )]) {
                            sh """
                                docker run -v /var/run/docker.sock:/var/run/docker.sock \\
                                    ${env.TRIVY_IMAGE} image ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} \\
                                    --exit-code 1 --severity HIGH,CRITICAL
                            """
                        }
                    }
                }
            }

            stage('Push Docker Image') {
                steps {
                    container('dind') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: env.DOCKER_CREDENTIALS_ID,
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
                    container('jnlp') {
                        script {
                            // Пример создания артефактов
                            archiveArtifacts artifacts: 'app/**/*.py', fingerprint: true

                            // Копирование Dockerfile и других важных файлов
                            sh "cp app/Dockerfile ${WORKSPACE}/Dockerfile"

                            // Добавление собранного образа для дальнейшего скачивания
                            sh """
                                docker save ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -o ${WORKSPACE}/${env.FULL_IMAGE_NAME}_${env.IMAGE_TAG}.tar
                            """
                            archiveArtifacts artifacts: "${WORKSPACE}/${env.FULL_IMAGE_NAME}_${env.IMAGE_TAG}.tar"
                        }
                    }
                }
            }

            stage('Update Manifests') {
                when {
                    expression {
                        env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'developer'
                    }
                }
                steps {
                    container('tools') {
                        script {
                            withCredentials([string(
                                credentialsId: env.GIT_CREDENTIALS_ID,
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
    }
}
