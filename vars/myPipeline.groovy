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
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        script {

                            checkout scm

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


                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' : "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                            
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

        stage('Update & Push') {
            steps {
                container('tools') {
                    withCredentials([string(credentialsId: 'GIT_TOKEN', variable: 'TOKEN')]) {
                        sh """
                            set -ex
                            git clone https://\${TOKEN}@github.com/arch-hcra/st31.git /tmp/repo || exit 1
                            cd /tmp/repo || exit 1

                            # Обновляем манифест
                            yq eval '.images[0].newTag = "st31-developer-103"' app-infra/overlays/dev/kustomization.yaml -i || exit 1

                            # Настраиваем git
                            git config --local user.email "jenkins@example.com"
                            git config --local user.name "Jenkins Bot"

                            # Синхронизируем ветку
                            git checkout developer || exit 1
                            git fetch origin || exit 1
                            git pull --rebase origin developer || {
                                echo "Конфликты при rebase. Применяем изменения 'theirs'."
                                git checkout --theirs app-infra/overlays/dev/kustomization.yaml || exit 1
                                git add app-infra/overlays/dev/kustomization.yaml || exit 1
                                git rebase --continue || exit 1
                            }

                            # Коммитим и пушим
                            git commit -m "chore: update image tag [skip ci]" || exit 1
                            git push origin developer || exit 1
                        """
                    }
                }
            }
        }
    


        }
    }
}