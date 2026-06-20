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
  - name: buildkit
    image: docker:24.0.7-dind
    command: ['dockerd-entrypoint.sh']
    args: ['--tls=false']
    env:
    - name: DOCKER_BUILDKIT
      value: "1"
    securityContext:
      privileged: false
  - name: python
    image: python:3.9
    command: ['cat']
    tty: true
  - name: tools
    image: alpine/kubectl:latest
    command: ['/bin/sh', '-c', 'apk add --no-cache curl git yq trivy && cat']
    tty: true
"""
        }
    }

    environment {
        // --- Git ---
        GIT_TOKEN = credentials('jenkins_1')
        GIT_EMAIL = "jenkins@ci.local"
        GIT_USER = "Jenkins CI"
        GIT_REPO_URL = "https://github.com/arch-hcra/st31.git"

        // --- Docker ---
        DOCKER_REGISTRY = "docker.io"
        DOCKER_CREDENTIALS = credentials('docker_token_1')
        DOCKER_IMAGE_NAME = "archcra/my-app"
        DOCKERFILE_PATH = "app/Dockerfile"
        CONTEXT_DIR = "app"

        // --- Config ---
        CONFIG_FILE = "app/.ci-config.yaml"
        TARGET_PATH = "app-infra/overlays/dev"

        // --- Trivy ---
        TRIVY_SEVERITY = "CRITICAL"
        TRIVY_REPORT = "trivy-report.json"
    }

    stages {
        stage('Checkout & Load Config') {
            steps {
                container('jnlp') {
                    script {
                        checkout scm

                        // Определяем GIT_BRANCH
                        env.GIT_BRANCH = env.BRANCH_NAME ?: 'developer'

                        // Загружаем конфиг
                        def cfg = readYaml file: "${env.CONFIG_FILE}"
                        env.DOCKER_IMAGE_NAME = cfg.dockerImage ?: env.DOCKER_IMAGE_NAME
                        env.TARGET_PATH = cfg.infraRepoTargetPath ?: env.TARGET_PATH

                        // Формируем полное имя образа и тег
                        env.FULL_IMAGE_NAME = "${env.DOCKER_REGISTRY}/${env.DOCKER_IMAGE_NAME}"
                        env.IMAGE_TAG = env.GIT_BRANCH == 'main' ? 'latest' : "${env.DOCKER_IMAGE_NAME}-${env.GIT_BRANCH}-${env.BUILD_NUMBER}"
                    }
                }
            }
        }

        stage('Build & Test') {
            steps {
                container('python') {
                    sh """
                        python3 -m venv venv
                        . venv/bin/activate
                        pip install --default-timeout=120 -r ${env.CONTEXT_DIR}/requirements.txt
                        pytest ${env.CONTEXT_DIR}/test/test_app.py
                    """
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                container('buildkit') {
                    script {
                        sh """
                            DOCKER_BUILDKIT=1 docker build \\
                              --progress=plain \\
                              -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} \\
                              -f ${env.DOCKERFILE_PATH} ${env.CONTEXT_DIR}
                        """
                    }
                }
            }
        }

        stage('Security Scan') {
            steps {
                container('tools') {
                    script {
                        sh """
                            docker pull ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                            trivy image --exit-code 1 --severity ${env.TRIVY_SEVERITY} ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                        """
                    }
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                container('buildkit') {
                    script {
                        withCredentials([usernamePassword(
                            credentialsId: 'docker_token_1',
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )]) {
                            sh """
                                echo ${DOCKER_PASS} | docker login -u ${DOCKER_USER} --password-stdin ${env.DOCKER_REGISTRY}
                                docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                                ${env.GIT_BRANCH == 'main' ? 'docker tag ' + env.FULL_IMAGE_NAME + ':' + env.IMAGE_TAG + ' ' + env.FULL_IMAGE_NAME + ':latest && docker push ' + env.FULL_IMAGE_NAME + ':latest' : ''}
                            """
                        }
                    }
                }
            }
        }

        stage('Update Manifests') {
            when { expression { env.GIT_BRANCH == 'main' || env.GIT_BRANCH == 'developer' } }
            steps {
                container('tools') {
                    script {
                        withCredentials([string(credentialsId: 'jenkins_1', variable: 'GIT_TOKEN')]) {
                            sh """
                                git clone https://${GIT_TOKEN}@${env.GIT_REPO_URL} /tmp/infra-repo
                                cd /tmp/infra-repo
                                git checkout ${env.GIT_BRANCH}
                                yq eval '.images[0].newTag = \"${env.IMAGE_TAG}\"' ${env.TARGET_PATH}/kustomization.yaml -i
                                git config --global user.email "${env.GIT_EMAIL}"
                                git config --global user.name "${env.GIT_USER}"
                                git add ${env.TARGET_PATH}/kustomization.yaml
                                git commit -m "chore: update image tag to ${env.IMAGE_TAG} [skip ci]"
                                git push https://${GIT_TOKEN}@${env.GIT_REPO_URL} HEAD:${env.GIT_BRANCH}
                            """
                        }
                    }
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                container('buildkit') {
                    script {
                        sh """
                            docker save ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} > ${env.ARTIFACT_IMAGE_TAR}
                        """
                        archiveArtifacts artifacts: "${env.ARTIFACT_IMAGE_TAR}", fingerprint: true
                    }
                }
                sh """
                    trivy image --format json --output ${env.TRIVY_REPORT} ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                """
                archiveArtifacts artifacts: "${env.TRIVY_REPORT}", fingerprint: true
            }
        }
    }

    post {
        always {
            sh "rm -rf ${WORKSPACE}/venv /tmp/infra-repo ${env.ARTIFACT_IMAGE_TAR} ${env.TRIVY_REPORT}"
        }
    }
}

}
