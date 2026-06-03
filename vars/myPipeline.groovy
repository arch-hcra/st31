def call(Map configParams) {

    def defaults = [
        k8sAgent: [
            jnlpImage: 'jenkins/inbound-agent:latest',
            dindImage: 'docker:dind',
            pythonImage: 'python:3.9',
            toolsImage: 'alpine/kubectl:latest',
        ],
  
        configDir: 'app',
        testFile: 'test/test_app.py',
        dockerfilePath: 'Dockerfile',
        requirementsFile: 'requirements.txt',
        dockerCredentialsId: 'docker_token_1',
        gitCredentialsId: 'jenkins_1',
        defaultBranch: 'developer',
        mainBranch: 'main',
        latestTag: 'latest',
        yqPath: '/usr/bin/yq',
    ]

    def mergedConfig = [:]
    defaults.each { key, value ->
        mergedConfig[key] = configParams[key] ?: value
    }

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
  - name: workspace-volume
    emptyDir: {}
  containers:
  - name: jnlp
    image: ${mergedConfig.jnlpImage}
    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
    volumeMounts:
      - name: workspace-volume
        mountPath: /home/jenkins/agent
  - name: dind
    image: ${mergedConfig.dindImage}
    command: ['dockerd-entrypoint.sh']
    args: ['--tls=false']
    securityContext:
      privileged: true
    volumeMounts:
      - name: workspace-volume
        mountPath: /home/jenkins/agent
  - name: python
    image: ${mergedConfig.pythonImage}
    command: ['cat']
    tty: true
    volumeMounts:
      - name: workspace-volume
        mountPath: /home/jenkins/agent
  - name: tools
    image: ${mergedConfig.toolsImage}
    command: ['/bin/sh', '-c', 'apk add --no-cache curl git ${mergedConfig.yqPath} && cat']
    tty: true
    volumeMounts:
      - name: workspace-volume
        mountPath: /home/jenkins/agent
"""
            }
        }

        environment {

            GIT_CREDENTIALS_ID: "${mergedConfig.gitCredentialsId}"
        }

        stages {
            stage('Checkout & Load Config') {
                steps {
                    container('jnlp') {
                        script {
                            checkout scm
                            env.BRANCH_NAME = env.BRANCH_NAME ?: mergedConfig.defaultBranch

                            def configFile = "${WORKSPACE}/${mergedConfig.configDir}/.ci-config.yaml"
                            if (!fileExists(configFile)) {
                                error("Config file not found at ${configFile}!")
                            }

                            def cfg = readYaml(file: configFile)
                            env.FULL_IMAGE_NAME = cfg.dockerImage ?: "docker.io/archcra/${cfg.appName}"
                            env.REPO_URL = cfg.infraRepoUrl ?: 'https://github.com/arch-hcra/st31.git'
                            env.TARGET_PATH = cfg.infraRepoTargetPath ?: 'app-infra/overlays/dev'
                            env.APP_NAME = cfg.appName
                            env.IMAGE_TAG = [
                                env.BRANCH_NAME == mergedConfig.mainBranch,
                                mergedConfig.latestTag,
                                "${env.APP_NAME}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                            ].find { it }
                        }
                    }
                }
            }

            stage('Build & Test') {
                steps {
                    container('python') {
                        script {
                            sh "pip install -r ${mergedConfig.configDir}/${mergedConfig.requirementsFile}"
                            sh "pytest ${mergedConfig.configDir}/${mergedConfig.testFile} -v"
                        }
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    container('dind') {
                        script {
                            sh """
                                docker build -t ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} \
                                -f ${mergedConfig.configDir}/${mergedConfig.dockerfilePath} \
                                ${mergedConfig.configDir}
                            """
                        }
                    }
                }
            }

            stage('Push Docker Image') {
                steps {
                    container('dind') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: mergedConfig.dockerCredentialsId,
                                usernameVariable: 'DOCKER_USER',
                                passwordVariable: 'DOCKER_PASS'
                            )]) {
                                sh """
                                    echo \${DOCKER_PASS} | docker login -u \${DOCKER_USER} --password-stdin
                                    docker push ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG}
                                """
                                if (env.BRANCH_NAME == mergedConfig.mainBranch) {
                                    sh """
                                        docker tag ${env.FULL_IMAGE_NAME}:${env.IMAGE_TAG} ${env.FULL_IMAGE_NAME}:${mergedConfig.latestTag}
                                        docker push ${env.FULL_IMAGE_NAME}:${mergedConfig.latestTag}
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Update Manifests') {
                when {
                    expression {
                        env.BRANCH_NAME == mergedConfig.mainBranch ||
                        env.BRANCH_NAME == mergedConfig.defaultBranch
                    }
                }
                steps {
                    container('tools') {
                        script {
                            withCredentials([string(
                                credentialsId: mergedConfig.gitCredentialsId,
                                variable: 'GIT_TOKEN'
                            )]) {
                                sh """
                                    git clone https://\${GIT_TOKEN}@github.com/arch-hcra/st31.git /tmp/infra-repo
                                    cd /tmp/infra-repo
                                    git checkout ${env.BRANCH_NAME}

                                    ${mergedConfig.yqPath} eval '.images[0].newTag = "${env.IMAGE_TAG}"' \
                                    ${env.TARGET_PATH}/kustomization.yaml -i

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
