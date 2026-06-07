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
  serviceAccountName: jenkins-agent-sa
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:latest
    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
    securityContext:
      allowPrivilegeEscalation: false
      readOnlyRootFilesystem: true
      runAsNonRoot: true
      runAsUser: 1000

  - name: python
    image: python:3.9
    command: ['cat']
    tty: true
    securityContext:
      runAsNonRoot: true
      runAsUser: 1000

  - name: tools
    image: alpine/kubectl:latest
    command: ['sh', '-c', 'apk add --no-cache curl git yq && cat']
    tty: true
    securityContext:
      runAsNonRoot: true
      runAsUser: 1000
      volumeMounts:
        - name: git-credentials
          mountPath: /root/.git-credentials
          subPath: token
        - name: docker-config
          mountPath: /root/.docker/config.json
          subPath: config.json

  - name: kaniko
    image: gcr.io/kaniko-project/executor:latest
    args: ["--single-snapshot", "--skip-tls-verify"]
    volumeMounts:
      - name: docker-config
        mountPath: /kaniko/.docker/config.json
        subPath: config.json
    securityContext:
      runAsUser: 1000
      runAsNonRoot: true
      allowPrivilegeEscalation: false
  volumes:
    - name: docker-config
      secret:
        secretName: kaniko-docker-credentials
    - name: git-credentials
      secret:
        secretName: git-credentials
"""
            }
        }

        environment {
            GIT_CREDENTIALS_ID = 'jenkins_1'
            DOCKER_CONFIG = file('kaniko-docker-credentials/config.json').text.trim()
            GIT_TOKEN = credentials('git-credentials').getPlainText() // <-- Используем credentials() для GIT_TOKEN
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
                                --context "${WORKSPACE}/app" \
                                --dockerfile "${WORKSPACE}/app/Dockerfile" \
                                --destination "${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}" \
                                --verbosity=debug \
                                --use-new-run \
                                --service-account-name=kaniko-pusher-sa
                            """
                        }
                    }
                }
            }

            stage('Push Latest Tag (if main)') {
                when {
                    expression { env.BRANCH_NAME == 'main' }
                }
                steps {
                    container('tools') {
                        script {
                            sh """
                                # Используем монтированный docker-config
                                kubectl create job docker-push-latest-${BUILD_NUMBER} \
                                  --namespace=jenkins \
                                  --image=docker:cli \
                                  -- \
                                  /bin/sh -c "
                                    cp /root/.docker/config.json /root/.docker/config.json &&
                                    docker login -u dummy -p dummy --username dummy --password-stdin < /root/.docker/config.json &&
                                    docker pull ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} &&
                                    docker tag ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} ${env.FULL_IMAGE_NAME}:latest &&
                                    docker push ${env.FULL_IMAGE_NAME}:latest
                                  " --serviceaccount=kaniko-pusher-sa
                            """
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
                            sh """
                                # Используем монтированный GIT_TOKEN
                                kubectl create job git-update-manifests-${BUILD_NUMBER} \
                                  --namespace=jenkins \
                                  --image=alpine/git \
                                  -- \
                                  /bin/sh -c "
                                    apk add --no-cache yq git &&
                                    mkdir -p /tmp/infra-repo &&
                                    git clone ${env.REPO_URL} /tmp/infra-repo &&
                                    cd /tmp/infra-repo &&
                                    git config --global user.email 'jenkins@ci.local' &&
                                    git config --global user.name 'Jenkins CI' &&
                                    # Используем yq для обновления тега
                                    yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i &&
                                    git add ${env.TARGET_PATH}/kustomization.yaml &&
                                    git commit -m 'chore: update image tag to ${env.IMAGE_TAG} [skip ci]' &&
                                    echo \"machine github.com login=jenkins password=${GIT_TOKEN}\" > ~/.netrc &&
                                    chmod 600 ~/.netrc &&
                                    git push https://github.com/arch-hcra/st31.git HEAD:${env.BRANCH_NAME}
                                  " --serviceaccount=git-updater-sa
                            """
                        }
                    }
                }
            }
        }
    }
}
