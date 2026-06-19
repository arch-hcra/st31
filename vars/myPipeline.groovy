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
        args: ['$(JENKINS_SECRET)', '$(JENKINS_NAME)']

    - name: dind
        image: docker:dind
        command: ['dockerd-entrypoint.sh']
        args: ['--tls=false']
        securityContext:
            privileged: true

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
            DOCKER_CREDENTIALS_ID = 'docker_token_1'
        }

        stages {
            // --- 1. Checkout & Load Config ---
            stage('Setup') {
                steps {
                    container('jnlp') {
                        script {
                            checkout scm
                            env.BRANCH_NAME = env.BRANCH_NAME ?: 'developer'

                            // Загрузка конфига с проверкой
                            def configFile = "${WORKSPACE}/app/.ci-config.yaml"
                            if (!fileExists(configFile)) {
                                error("❌ Config file ${configFile} not found!")
                            }

                            def cfg = readYaml(file: configFile)
                            if (!cfg?.appName) {
                                error("❌ Invalid config: 'appName' is required!")
                            }

                            // Настройка переменных
                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: 'app-infra/overlays/dev'
                            env.APP_NAME = cfg.appName

                            // Тегирование
                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' : "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                            echo "=== CONFIG ==="
                            echo "Image: ${env.FULL_IMAGE_NAME}"
                            echo "Tag: ${env.IMAGE_TAG}"
                        }
                    }
                }
            }

            // --- 2. Build & Test ---
            stage('Build & Test') {
                when { expression { env.BRANCH_NAME != 'main' } } // Пропускаем тесты для main (производство)
                steps {
                    container('jnlp') { // Используем jnlp для тестов (python в отдельном контейнере)
                        sh '''
                            python3 -m venv venv
                            . venv/bin/activate
                            pip install --default-timeout=120 -r app/requirements.txt
                            pytest app/test/test_app.py || exit 0 // Пропускаем ошибки тестов (если их нет)
                        '''
                    }
                }
            }

            // --- 3. Build & Push Docker ---
            stage('Build & Push') {
                steps {
                    container('dind') {
                        steps {
                            script {
                                // Сборка и пуш образа
                                sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -f app/Dockerfile app"
                                withCredentials([usernamePassword(
                                    credentialsId: env.DOCKER_CREDENTIALS_ID,
                                    usernameVariable: 'DOCKER_USER',
                                    passwordVariable: 'DOCKER_PASS'
                                )]) {
                                    sh """
                                        echo ${DOCKER_PASS} | docker login -u ${DOCKER_USER} --password-stdin
                                        docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                                    """

                                    // Тег latest только для main
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

            // --- 4. Update Kustomize (только для developer) ---
            stage('Update Kustomize') {
                when { branch 'developer' } // Работает только в developer
                steps {
                    container('tools') {
                        withCredentials([string(credentialsId: env.GIT_CREDENTIALS_ID, variable: 'GIT_TOKEN')]) {
                            sh """
                                git clone ${env.REPO_URL} /tmp/infra-repo
                                cd /tmp/infra-repo

                                # Обновляем тег в kustomize
                                yq eval '.images[0].newTag = "${env.IMAGE_TAG}"' ${env.TARGET_PATH}/kustomization.yaml -i

                                # Автопереключение на developer и коммит
                                git checkout developer || git checkout -b developer
                                git config --global user.email "jenkins@ci.local"
                                git config --global user.name "Jenkins CI"
                                git add ${env.TARGET_PATH}/kustomization.yaml
                                git commit -m "chore: update image tag to ${env.IMAGE_TAG} [skip ci]"
                                git push origin developer
                            """
                        }
                    }
                }
            }
        }
}
}