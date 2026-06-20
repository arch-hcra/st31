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
            // Основные переменные окружения
            DEFAULT_BRANCH = 'developer'
            CONFIG_FILE = "${WORKSPACE}/app/.ci-config.yaml"
            ARTIFACT_DIR = "${WORKSPACE}/artifacts"
            DOCKER_CREDENTIALS = 'docker_token_1'
            GIT_TOKEN_CREDENTIALS = 'jenkins_1'

            // Переменные из конфига
            APP_NAME = ''
            FULL_IMAGE_NAME = ''
            REPO_URL = 'https://github.com/arch-hcra/st31.git'
            TARGET_PATH = 'app-infra/overlays/dev'

            // Логические переменные
            IS_MAIN_BRANCH = "${BRANCH_NAME} == 'main'"
            IS_DEVELOP_BRANCH = "${BRANCH_NAME} == 'developer'"
            SHOULD_UPDATE_MANIFESTS = "${IS_MAIN_BRANCH} || ${IS_DEVELOP_BRANCH}"
            SHOULD_SCAN_SECURITY = "${BRANCH_NAME} != 'main'"
        }

        stages {
            stage('Prepare') {
                steps {
                    script {
                        // Создаем директорию для артефактов
                        dir(env.ARTIFACT_DIR) {
                            createDir()  // Используем createDir() вместо mkdir
                        } || error("Failed to create ${env.ARTIFACT_DIR}")

                        // Выгружаем репозиторий
                        checkout scm

                        // Проверяем наличие конфигурационного файла
                        if (!fileExists(env.CONFIG_FILE)) {
                            error("Config file ${env.CONFIG_FILE} not found!")
                        }

                        // Загружаем конфиг
                        def cfg = readYaml file: env.CONFIG_FILE
                        if (!cfg || !cfg.appName) {
                            error("Invalid config: 'appName' is required!")
                        }

                        // Настраиваем переменные окружения
                        env.APP_NAME = cfg.appName
                        env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${env.APP_NAME}"
                        env.REPO_URL = cfg.infraRepoUrl ?: env.REPO_URL
                        env.TARGET_PATH = cfg.infraRepoTargetPath ?: env.TARGET_PATH
                        env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' : "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"

                        echo "=== CONFIG LOADED ==="
                        echo "Image: ${env.FULL_IMAGE_NAME}"
                        echo "Tag: ${env.IMAGE_TAG}"
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

            stage('Security Scan') {
                when {
                    expression { return env.SHOULD_SCAN_SECURITY == 'true' }
                }
                steps {
                    container('tools') {
                        sh '''
                            trivy image --exit-code 1 --severity CRITICAL ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                        '''
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
                        withCredentials([usernamePassword(
                            credentialsId: env.DOCKER_CREDENTIALS,
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )]) {
                            script {
                                sh "echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin"
                                sh "docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}"

                                if (env.IS_MAIN_BRANCH == 'true') {
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
                    expression { return env.SHOULD_UPDATE_MANIFESTS == 'true' }
                }
                steps {
                    container('tools') {
                        withCredentials([string(
                            credentialsId: env.GIT_TOKEN_CREDENTIALS,
                            variable: 'GIT_TOKEN'
                        )]) {
                            sh '''
                                git clone https://\$GIT_TOKEN@github.com/arch-hcra/st31.git /tmp/infra-repo
                                cd /tmp/infra-repo
                                git checkout ${env.BRANCH_NAME}

                                yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i

                                git config --global user.email "jenkins@ci.local"
                                git config --global user.name "Jenkins CI"

                                git add ${env.TARGET_PATH}/kustomization.yaml
                                git commit -m "chore: update image tag to ${env.IMAGE_TAG}"
                                git push https://\$GIT_TOKEN@github.com/arch-hcra/st31.git HEAD:${env.BRANCH_NAME}
                            '''
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    // Архивирование артефактов
                    archiveArtifacts artifacts: "${env.ARTIFACT_DIR}/*.tar", fingerprint: true
                }
            }
        }
    }
}
