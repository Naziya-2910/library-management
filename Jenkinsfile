pipeline {
    agent any

    options {
        disableConcurrentBuilds()   // two builds must not push to Git at the same time
        timestamps()
    }

    environment {
        SONARQUBE_SERVER = 'SonarQubeServer'
        PATH             = "/opt/sonar-scanner/bin:${PATH}"
        AWS_REGION       = 'ap-south-1'
        ECR_REGISTRY     = '822426641221.dkr.ecr.ap-south-1.amazonaws.com'
        API_REPO         = '822426641221.dkr.ecr.ap-south-1.amazonaws.com/librarycatalogue'
        UI_REPO          = '822426641221.dkr.ecr.ap-south-1.amazonaws.com/libraryshop'
        DEPLOYMENT_REPO  = 'https://github.com/Naziya-2910/library-management.git'
        // Manifests that Argo CD watches; the image line in each is updated on every build
        API_MANIFEST     = 'kubernetes/librarycatalogue/deployment.yaml'
        UI_MANIFEST      = 'kubernetes/libraryshop/deployment.yaml'
        // Trivy gate: '0' = report CRITICAL findings but keep going, '1' = fail the build on them.
        // Set to '1' once the Spring Boot dependencies are upgraded.
        TRIVY_EXIT_CODE  = '0'
    }

    stages {

        // 1. Get the code
        stage('Checkout') {
            steps {
                git branch: 'main',
                    credentialsId: 'git-cred',
                    url: 'https://github.com/Naziya-2910/library-management.git'
            }
        }

        // 2. One tag for both images: <build number>-<short commit hash>
        stage('Set Image Tag') {
            steps {
                script {
                    def shortHash = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()

                    env.IMAGE_TAG = "${BUILD_NUMBER}-${shortHash}"
                    echo "Using image tag: ${env.IMAGE_TAG}"
                }
            }
        }

        // 3. Build and test the API (the JAR must exist before the Docker build)
        stage('Maven Build and Test') {
            steps {
                sh '''
                    docker run --rm \
                      -u $(id -u):$(id -g) \
                      -e HOME=/tmp \
                      -v "$PWD/librarycatalogue":/build -w /build \
                      maven:3.9-eclipse-temurin-21 \
                      mvn -B clean verify -Dmaven.repo.local=/tmp/.m2
                '''
            }
            post {
                always {
                    junit testResults: 'librarycatalogue/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        // 4. Code quality and security analysis of the API
        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER}") {
                    sh '''
                        sonar-scanner \
                          -Dsonar.projectKey=library-management \
                          -Dsonar.projectName=library-management \
                          -Dsonar.projectBaseDir=librarycatalogue \
                          -Dsonar.sources=src \
                          -Dsonar.java.binaries=target/classes
                    '''
                }
            }
        }

        // 5. Build both images
        stage('Build Docker Images') {
            steps {
                sh '''
                    docker build -t "librarycatalogue:$IMAGE_TAG" ./librarycatalogue
                    docker build -t "libraryshop:$IMAGE_TAG" ./libraryshop
                '''
            }
        }

        // 6. Trivy: save reports, then check fixable CRITICAL vulnerabilities (blocking only if TRIVY_EXIT_CODE=1)
        stage('Image Scan') {
            steps {
                sh '''
                    trivy image --exit-code 0 --output trivy-api-report.txt "librarycatalogue:$IMAGE_TAG"
                    trivy image --exit-code 0 --output trivy-ui-report.txt "libraryshop:$IMAGE_TAG"
                    trivy image --exit-code "$TRIVY_EXIT_CODE" --severity CRITICAL --ignore-unfixed "librarycatalogue:$IMAGE_TAG"
                    trivy image --exit-code "$TRIVY_EXIT_CODE" --severity CRITICAL --ignore-unfixed "libraryshop:$IMAGE_TAG"
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'trivy-*-report.txt', allowEmptyArchive: true
                }
            }
        }

        // 7. Push both images to their ECR repositories
        stage('Push to ECR') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-cred'
                ]]) {
                    sh '''
                        set -eu

                        aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"

                        docker tag "librarycatalogue:$IMAGE_TAG" "$API_REPO:$IMAGE_TAG"
                        docker push "$API_REPO:$IMAGE_TAG"

                        docker tag "libraryshop:$IMAGE_TAG" "$UI_REPO:$IMAGE_TAG"
                        docker push "$UI_REPO:$IMAGE_TAG"
                    '''
                }
            }
        }

        // 8. Write both new tags into Git so Argo CD deploys them
        stage('Update GitOps Repo') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'git-cred',
                    usernameVariable: 'GIT_USERNAME',
                    passwordVariable: 'GIT_PASSWORD'
                )]) {
                    sh '''
                        set -eu

                        ASKPASS="$WORKSPACE/git-askpass.sh"

                        cat > "$ASKPASS" <<'EOF'
#!/bin/sh
case "$1" in
    *Username*) printf '%s\\n' "$GIT_USERNAME" ;;
    *Password*) printf '%s\\n' "$GIT_PASSWORD" ;;
esac
EOF

                        chmod 700 "$ASKPASS"

                        export GIT_ASKPASS="$ASKPASS"
                        export GIT_TERMINAL_PROMPT=0

                        rm -rf gitops-repo

                        git clone "$DEPLOYMENT_REPO" gitops-repo

                        cd gitops-repo

                        echo "Before update:"
                        grep -n 'image:' "$API_MANIFEST" "$UI_MANIFEST"

                        sed -i -E \
                            "s|^([[:space:]]*image:[[:space:]]*).*|\\1${API_REPO}:${IMAGE_TAG}|" \
                            "$API_MANIFEST"

                        sed -i -E \
                            "s|^([[:space:]]*image:[[:space:]]*).*|\\1${UI_REPO}:${IMAGE_TAG}|" \
                            "$UI_MANIFEST"

                        echo "After update:"
                        grep -n 'image:' "$API_MANIFEST" "$UI_MANIFEST"

                        git config user.email "jenkins@local"
                        git config user.name "Jenkins"

                        git add "$API_MANIFEST" "$UI_MANIFEST"

                        # Commit only if something actually changed
                        if git diff --cached --quiet; then
                            echo "No change to commit."
                        else
                            git commit -m "Update image tags to ${IMAGE_TAG} [skip ci]"
                            # Pick up any commit pushed since the clone, then push
                            git pull --rebase origin main
                            git push origin HEAD:main
                        fi

                        rm -f "$ASKPASS"
                    '''
                }
            }
        }
    }

    post {
        always {
            // Free disk space on the Jenkins server and remove the temporary clone
            sh '''
                docker rmi "librarycatalogue:$IMAGE_TAG" "libraryshop:$IMAGE_TAG" \
                           "$API_REPO:$IMAGE_TAG" "$UI_REPO:$IMAGE_TAG" || true
                rm -rf gitops-repo git-askpass.sh
            '''
        }
        success {
            echo "Pushed ${API_REPO}:${IMAGE_TAG} and ${UI_REPO}:${IMAGE_TAG}. Argo CD will deploy them."
        }
        failure {
            echo 'Build failed. Check the failed stage in the console log.'
        }
    }
}
