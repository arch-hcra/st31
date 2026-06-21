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

        // Используем пост-скрипт для установки переменных окружения
        environment {
            // Переменные окружения устанавливаем отдельно
            GIT_CREDENTIALS_ID = 'default_credentials'
            DOCKER_CREDENTIALS_ID = 'default_docker_token'
            TRIVY_IMAGE = 'aquasec/trivy:latest'
        }

        // Устанавливаем значения параметров после блока environment
        script {
            if (configParams.gitCredentialsId) {
                env.GIT_CREDENTIALS_ID = configParams.gitCredentialsId
            }
            if (configParams.dockerCredentialsId) {
                env.DOCKER_CREDENTIALS_ID = configParams.dockerCredentialsId
            }
            if (configParams.trivyImage) {
                env.TRIVY_IMAGE = configParams.trivyImage
            }
            if (configParams.defaultBranch) {
                env.BRANCH_NAME = configParams.defaultBranch
            }
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        script {
                            checkout scm

                            // Пример загрузки конфигурации
                            def configFile = "${WORKSPACE}/app/.ci-config.yaml"
                            if (!fileExists(configFile)) {
                                error("Config file ${configFile} not found!")
                            }
                            def cfg = readYaml(file: configFile)

                            if (!cfg || !cfg.appName) {
                                error("Invalid config: 'appName' is required!")
                            }

                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: env.REPO_URL ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: env.TARGET_PATH ?: 'app-infra/overlays/dev'
                            env.APP_NAME = cfg.appName

                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' :
                                "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                        }
                    }
                }
            }

            // Остальные stages остаются без изменений
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
                        sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -f app/Dockerfile app"
                    }
                }
            }

            stage('Scan for Vulnerabilities') {
                steps {
                    container('tools') {
                        withCredentials([usernamePassword(
                            credentialsId: env.GIT_CREDENTIALS_ID,
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
    }
}
