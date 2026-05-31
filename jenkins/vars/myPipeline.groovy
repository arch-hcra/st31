def call(Map config) {
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
  serviceAccountName: jenkins
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:latest
    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
    
  # Контейнер с Docker-демоном (DinD)
  - name: dind
    image: docker:dind
    command: ['dockerd-entrypoint.sh']
    args: ['--tls=false']
    securityContext:
      privileged: true
      
  # Клиент Docker
  - name: docker
    image: docker:latest
    command: ['cat']
    tty: true
    env:
      - name: DOCKER_HOST
        value: tcp://localhost:2375
      - name: DOCKER_TLS_CERTDIR
        value: ""

  # Python для тестов
  - name: python
    image: python:3.9
    command: ['cat']
    tty: true

  # Tools для деплоя (kubectl + yq)
  - name: tools
    image: alpine/kubectl:latest
    command: ['cat']
    tty: true
    # Устанавливаем yq при старте контейнера
    command:
    - /bin/sh
    - -c
    - |
      apk add --no-cache curl
      curl -L https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -o /usr/bin/yq && chmod +x /usr/bin/yq
      cat
"""
            }
        }
        
        parameters {
            string(name: 'DOCKER_IMAGE_NAME', defaultValue: 'st31', description: 'Имя Docker образа')
            string(name: 'DOCKER_REGISTRY', defaultValue: 'docker.io/archcra', description: 'Docker Registry')
        }

        environment {

            IMAGE_TAG_BASE = "${env.BUILD_NUMBER}"
            FULL_IMAGE_NAME = "${params.DOCKER_REGISTRY}/${params.DOCKER_IMAGE_NAME}"

            IMAGE_TAG = "" 
        }

        stages {
            
            stage('Checkout & Tag') {
                steps {
                    container('jnlp') {
                        checkout scm
                        script {

                            def shortHash = gitCommitShortHash()
                            env.IMAGE_TAG = "${env.IMAGE_TAG_BASE}-${shortHash}"
                            
                            echo "Current branch: ${env.BRANCH_NAME}"
                            echo "Is PR? ${env.CHANGE_ID != null}"
                            echo "Final Image Tag: ${env.IMAGE_TAG}"
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
                                pip install -r requirements.txt
                                python3 -m unittest test_app.py
                            '''
                            echo "Running unit tests... OK"
                        }
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    container('docker') {
                        script {
                            echo "Building Docker image: ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
                            sh "docker build -t ${FULL_IMAGE_NAME}:${IMAGE_TAG} ."
                        }
                    }
                }
            }

            stage('Push Docker Image') {
                steps {
                    container('docker') {
                        script {
                            echo "Pushing Docker image..."
                            withCredentials([usernamePassword(credentialsId: 'docker_token_1', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                                sh "echo ${DOCKER_PASS} | docker login -u ${DOCKER_USER} --password-stdin"
                                
                                sh "docker push ${FULL_IMAGE_NAME}:${IMAGE_TAG}"
                                
                                sh "docker tag ${FULL_IMAGE_NAME}:${IMAGE_TAG} ${FULL_IMAGE_NAME}:latest"
                                sh "docker push ${FULL_IMAGE_NAME}:latest"
                            }
                            echo "Image pushed successfully!"
                        }
                    }
                }
            }

            stage('Deploy to Dev') {
                when {
                    branch 'main'
                }
                steps {
                    script {
                        echo "Merged to main! Starting deployment to dev namespace..."
                        
                        dir('infra-repo') {

                            git url: 'https://github.com/arch-hcra/st31.git', branch: 'main'
                            

                            container('tools') {
                                sh "yq --version"
                                sh """
                                    yq e '.images[0].newTag = "${IMAGE_TAG}"' -i overlays/dev/kustomization.yaml
                                    echo "Updated image tag in kustomization to ${IMAGE_TAG}"
                                """
                                

                                withCredentials([file(credentialsId: 'kubeconfig-dev', variable: 'KUBECONFIG')]) {
                                    sh "kubectl apply -k overlays/dev/"
                                }
                            }
                            
                            echo "Deployment to dev successful!"
                        }
                    }
                }
            }
        }
    }
}

def gitCommitShortHash() {
    return sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
}
