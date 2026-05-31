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
      

  - name: docker
    image: docker:latest
    command: ['cat']
    tty: true
    env:
      - name: DOCKER_HOST
        value: tcp://localhost:2375
      - name: DOCKER_TLS_CERTDIR
        value: ""


  - name: python
    image: python:3.9
    command: ['cat']
    tty: true

 
  - name: tools
    image: alpine/kubectl:latest
    command: ['cat']
    tty: true

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
        


        environment {
            IMAGE_TAG_BASE = "${env.BUILD_NUMBER}"

            FULL_IMAGE_NAME = "" 
            REPO_URL = ""
            TARGET_PATH = ""
            
            IMAGE_TAG = "" 
            GIT_CREDENTIALS_ID = 'jenkins_1' 
        }

        stages {
            
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        checkout scm
                        script {

                            def cfg = readYaml file: configParams.configFile
                        
                            env.FULL_IMAGE_NAME = cfg.dockerImage
                            env.REPO_URL = cfg.infraRepoUrl
                            env.TARGET_PATH = cfg.infraRepoTargetPath
                            
  
                            def shortHash = gitCommitShortHash()
                            env.IMAGE_TAG = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}-${shortHash}"
                            
                            echo "=== Config Loaded ==="
                            echo "App Name: ${cfg.appName}"
                            echo "Docker Image: ${env.FULL_IMAGE_NAME}"
                            echo "Target Path: ${env.TARGET_PATH}"
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
                        }
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    container('docker') {
                        script {
                            sh "docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} ."
                        }
                    }
                }
            }

            stage('Push Docker Image') {
                steps {
                    container('docker') {
                        script {
                            withCredentials([usernamePassword(credentialsId: 'docker_token_1', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                                sh "echo ${DOCKER_PASS} | docker login -u ${DOCKER_USER} --password-stdin"
                                sh "docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}"
                                
                               
                                if (env.BRANCH_NAME == 'main') {
                                    sh "docker tag ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} ${env.FULL_IMAGE_NAME}:latest"
                                    sh "docker push ${env.FULL_IMAGE_NAME}:latest"
                                }
                            }
                        }
                    }
                }
            }

  
            stage('Update Manifests & Push to Git') {
                steps {
                    script {
                        if (env.BRANCH_NAME != 'developer' && env.BRANCH_NAME != 'main') {
                            echo "Branch ${env.BRANCH_NAME} is not configured for auto-deploy update. Skipping."
                            return 
                        }

                        echo "Updating manifest in ${env.TARGET_PATH}/kustomization.yaml with tag ${env.IMAGE_TAG}"
                        
                        container('tools') {
                            sh "yq e '.images[0].newTag = \"${env.IMAGE_TAG}\"' -i ${env.TARGET_PATH}/kustomization.yaml"
                            
                            sh """
                                git config user.email "jenkins@ci.local"
                                git config user.name "Jenkins CI"
                            """
                            
                            withCredentials([usernamePassword(credentialsId: "${GIT_CREDENTIALS_ID}", usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {

                                sh """
                                    AUTH_URL=\$(echo ${env.REPO_URL} | sed -e 's|https://||')
                                    git add ${env.TARGET_PATH}/kustomization.yaml
                                    git commit -m "Update image tag to ${env.IMAGE_TAG} in ${env.TARGET_PATH} [skip ci]"
                                    git push https://${GIT_USER}:\${GIT_TOKEN}@\${AUTH_URL} HEAD:${env.BRANCH_NAME}
                                """
                            }
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
