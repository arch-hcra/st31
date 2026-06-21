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
  volumes:
    - name: docker-sock
      hostPath:
        path: /var/run/docker.sock
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest
      args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
      volumeMounts:
        - name: docker-sock
          mountPath: /var/run/docker.sock

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
      command: ['/bin/sh', '-c', 'apk add --no-cache curl git yq && cat']
      tty: true
      volumeMounts:
        - name: docker-sock
          mountPath: /var/run/docker.sock

    - name: trivy
      image: aquasec/trivy:latest
      command: ['cat']
      tty: true
      volumeMounts:
        - name: docker-sock
          mountPath: /var/run/docker.sock
"""

            }
        }

        // Блок конфиденциальных данных (должен быть заполнен в Jenkins)
        environment {
            // Git credentials (используется в нескольких местах)
            GIT_CREDENTIALS_ID = 'jenkins_1'

            // Docker credentials
            DOCKER_CREDENTIALS_ID = 'docker_token_1'

            // Дополнительные переменные для удобства
            GIT_REPO_URL = 'https://github.com/arch-hcra/st31.git'
            GIT_DEFAULT_BRANCH = 'developer'
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    script {
                        checkout scm

                        env.BRANCH_NAME = env.BRANCH_NAME ?: env.GIT_DEFAULT_BRANCH

                        def configFile = "${WORKSPACE}/app/.ci-config.yaml"
                        if (!fileExists(configFile)) {
                            error("Config file ${configFile} not found!")
                        }

                        def cfg = readYaml(file: configFile)

                        if (!cfg || !cfg.appName) {
                            error("Invalid config: 'appName' is required!")
                        }

                        env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                        env.REPO_URL = cfg.infraRepoUrl ?: env.GIT_REPO_URL
                        env.TARGET_PATH = cfg.infraRepoTargetPath ?: 'app-infra/overlays/dev'
                        env.APP_NAME = cfg.appName

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

            stage('Build Docker Image') {
                steps {
                    container('dind') {
                        script {
                            sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} -f app/Dockerfile app"
                        }
                    }
                }
            }

            stage('Scan Docker Image') {
                when {
                    expression { env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'developer' }
                }
                steps {
                    container('dind') {
                        script {
                            // Проверка наличия образа
                            def imageCheck = sh(script: "docker images | grep ${env.IMAGE_TAG}", returnStdout: true)
                            if (!imageCheck.trim().contains(env.IMAGE_TAG)) {
                                error("Image ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} not found!")
                            }

                            // Установка Trivy
                            sh "apk add --no-cache curl"
                            sh "curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin"

                            // --- Скан уязвимостей (без прерывания) ---
                            def vulnerabilitiesReport = sh(
                                script: """
                                    /usr/local/bin/trivy image \\
                                        --format table \\
                                        --severity CRITICAL,HIGH,MEDIUM,LOW \\
                                        ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                                """,
                                returnStdout: true
                            )

                            // Сохранение отчёта в текстовый файл
                            writeFile file: "trivy-vulnerabilities-${env.BUILD_NUMBER}.txt", text: vulnerabilitiesReport

                            // Сохранение JSON-отчёта для интеграции с другими инструментами
                            sh "/usr/local/bin/trivy image --format json --output ${WORKSPACE}/trivy-report-${env.BUILD_NUMBER}.json ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}"

                            // Архивация артефактов
                            archiveArtifacts artifacts: 'trivy-vulnerabilities-*.txt', fingerprint: true
                            archiveArtifacts artifacts: 'trivy-report-*.json', fingerprint: true

                            // Логирование результатов
                            echo "=== Trivy Vulnerability Scan Results ==="
                            echo vulnerabilitiesReport
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

            stage('Update Manifests') {
                when {
                    expression { env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'developer' }
                }
                steps {
                    container('tools') {
                        script {
                            withCredentials([
                                string(credentialsId: env.GIT_CREDENTIALS_ID, variable: 'GIT_TOKEN'),
                                usernamePassword(
                                    credentialsId: env.DOCKER_CREDENTIALS_ID,
                                    usernameVariable: 'DOCKER_USER',
                                    passwordVariable: 'DOCKER_PASS'
                                )
                            ]) {
                                sh """
                                    git clone https://\${GIT_TOKEN}@github.com/arch-hcra/st31.git /tmp/infra-repo
                                    cd /tmp/infra-repo
                                    git checkout ${env.BRANCH_NAME}

                                    yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i

                                    git config --global user.email "jenkins@ci.local"
                                    git config --global user.name "Jenkins CI"

                                    git add ${env.TARGET_PATH}/kustomization.yaml
                                    git commit -m "chore: update image tag to ${env.IMAGE_TAG} [skip ci]"
                                    git push https://\${GIT_TOKEN}@github.com/arch-hcra/st31.git HEAD:${env.BRANCH_NAME}
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
