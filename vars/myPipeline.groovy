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

 
  - name: kaniko
    image: gcr.io/kaniko-project/executor:latest
    args: ["--dockerfile=app/Dockerfile", "--context=dir:///workspace/app"]

    volumeMounts:
    - name: docker-config
      mountPath: /kaniko/.docker
    securityContext:
      runAsUser: 0 
  volumes:
  - name: docker-config
    secret:
      secretName: regcred  
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
                            if (!fileExists(configFile)) error("Config file not found!")
                            def cfg = readYaml(file: configFile)
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

            stage('Build Docker Image with Kaniko') {
                steps {
                    container('kaniko') {
                        script {
  
                            sh """
                                /kaniko/executor \
                                  --context=dir:///workspace/app \
                                  --dockerfile=app/Dockerfile \
                                  --destination=${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} \
                                  --verbosity=debug
                            """
                            if (env.BRANCH_NAME == 'main') {
                                sh """
                                    /kaniko/executor \
                                      --context=dir:///workspace/app \
                                      --dockerfile=app/Dockerfile \
                                      --destination=${env.FULL_IMAGE_NAME}:latest \
                                      --verbosity=debug
                                """
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
