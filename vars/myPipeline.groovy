def call(Map configParams) {
    pipeline {
        agent {
        kubernetes {
            yaml """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: jenkins-agent-${BUILD_ID}
                spec:
                  containers:
                  - name: jnlp
                    image: jenkins/jnlp-agent:latest
                    resources:
                      limits:
                        memory: "2Gi"
                        cpu: "1"
                  - name: dind
                    image: docker:dind-rootless
                    securityContext:
                      runAsUser: 1000
                      privileged: false
                    volumeMounts:
                    - name: docker-sock
                      mountPath: /var/run/docker-sock.sock
                    resources:
                      limits:
                        memory: "4Gi"
                  - name: python
                    image: python:3.9-slim
                    volumeMounts:
                    - name: docker-sock
                      mountPath: /var/run/docker-sock.sock
                  - name: tools
                    image: alpine/git
                  volumes:
                  - name: docker-sock
                    emptyDir: {}
                  - name: workspace
                    emptyDir: {}
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
                                error("Config file not found!")
                            }

                            def cfg = readYaml(file: configFile)
                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: 'app-infra/overlays/dev'
                            env.APP_NAME = cfg.appName
                            env.IMAGE_TAG = env.BRANCH_NAME == 'main' ? 'latest' : "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                        }
                    }
                }
            }

            stage('Build & Test') {
                steps {
                    container('python') {
                        script {
                                sh """
                                    pip install --upgrade pip
                                    pip install -r app/requirements.txt --user
                                """
                                sh "pytest app/test/test_app.py -v --junitxml=report.xml"
                                junit '**/report.xml'
                        }
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
                                    
                                    yq eval '.images[0].newTag = "${env.IMAGE_TAG}"' ${env.TARGET_PATH}/kustomization.yaml -i

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
