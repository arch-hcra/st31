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
      apk add --no-cache curl git yq
      cat
    tty: true
"""
            }
        }

        environment {
            GIT_CREDENTIALS_ID = 'jenkins_1'
            // Параметр для защиты от бесконечных вебхуков
            SKIP_WEBHOOK_PROCESSING = (configParams?.SKIP_WEBHOOK_PROCESSING ?: false).toString()
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        script {
                            checkout scm

                            // Блокируем выполнение для main ветки
                            if (env.BRANCH_NAME == 'main') {
                                error("Manual merge required for main branch!")
                            }

                            env.BRANCH_NAME = env.BRANCH_NAME ?: 'developer'

                            def configFile = "${WORKSPACE}/app/.ci-config.yaml"
                            if (!fileExists(configFile)) {
                                error("Config file ${configFile} not found!")
                            }

                            def cfg = readYaml(file: configFile)

                            if (!cfg || !cfg.appName) {
                                error("Invalid config: 'appName' is required!")
                            }

                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: 'app-infra/overlays/dev'
                            env.APP_NAME = cfg.appName

                            env.IMAGE_TAG = "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                            if (env.BRANCH_NAME == 'developer') {
                                env.IMAGE_TAG = "${env.APP_NAME}-dev-${env.BUILD_NUMBER}"
                            }

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
                        sh '''
                            python3 -m venv venv
                            . venv/bin/activate
                            pip install --default-timeout=120 -r app/requirements.txt
                            pytest app/test/test_app.py || exit 1
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
                            }
                        }
                    }
                }
            }

 
            stage('Update Manifests') {
                when {
                    expression { env.BRANCH_NAME == 'developer' }
                }
                steps {
                    container('tools') {
                        script {
                            withCredentials([string(
                                credentialsId: 'jenkins_1',
                                variable: 'GIT_TOKEN'
                            )]) {
                                // Проверяем параметр конфигурации правильно
                                def skipProcessing = (configParams?.SKIP_WEBHOOK_PROCESSING ?: false).toString()

                                if (skipProcessing == 'true') {
                                    echo "Skipping manifest update (already processed by webhook)"
                                    return
                                }

                                // Клонируем репозиторий
                                sh "git clone https://${GIT_TOKEN}@github.com/arch-hcra/st31.git /tmp/infra-repo"

                                // Проверяем текущий тег в kustomization
                                def currentTag = sh(script: "cd /tmp/infra-repo && yq eval '.images[0].newTag' ${env.TARGET_PATH}/kustomization.yaml", returnStdout: true).trim()

                                // Если тег уже обновлён - не делаем ничего
                                if (currentTag == env.IMAGE_TAG) {
                                    echo "Image tag already up to date in manifests"
                                    return
                                }

                                // Обновляем тег
                                sh """
                                    cd /tmp/infra-repo
                                    git checkout ${env.BRANCH_NAME}

                                    yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i

                                    git config --global user.email "jenkins@ci.local"
                                    git config --global user.name "Jenkins CI"

                                    git add ${env.TARGET_PATH}/kustomization.yaml
                                    git diff --cached --quiet || git commit -m "chore: update image tag to ${env.IMAGE_TAG}"
                                """

                                // Проверяем, были ли изменения
                                def changes = sh(script: "cd /tmp/infra-repo && git diff --name-only", returnStdout: true).trim()
                                if (changes) {
                                    sh """
                                        cd /tmp/infra-repo
                                        git push https://${GIT_TOKEN}@github.com/arch-hcra/st31.git HEAD:${env.BRANCH_NAME}
                                    """
                                } else {
                                    echo "No changes detected in manifests"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
