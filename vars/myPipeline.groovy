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
    securityContext:
      privileged: true
  - name: python
    image: python:3.9
  - name: tools
    image: alpine/kubectl:latest
    command: ['sh', '-c', 'apk add --no-cache curl git yq trivy && cat']
    tty: true
"""
            }
        }

        environment {
            // Базовые переменные окружения
            GIT_CREDENTIALS_ID = configParams.gitCredentialsId ?: 'default_credentials'
            DOCKER_CREDENTIALS_ID = configParams.dockerCredentialsId ?: 'default_docker_token'
            TRIVY_IMAGE = configParams.trivyImage ?: 'aquasec/trivy:latest'
            BRANCH_NAME = configParams.defaultBranch ?: env.BRANCH_NAME ?: 'main'
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        checkout scm

                        // Чтение конфига и установка переменных
                        script {
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
                        script {
                            // Используем `withCredentials` внутри `script`
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

                                // Условие внутри `script` (не в `steps`!)
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
}
