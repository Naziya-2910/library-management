pipeline {
    agent any

    parameters {
        string(name: 'AWS_ACCOUNT_ID', defaultValue: '', description: 'AWS account ID containing the ECR repositories')
        string(name: 'AWS_REGION', defaultValue: 'us-east-1', description: 'AWS region for ECR and EKS')
        string(name: 'EKS_CLUSTER', defaultValue: '', description: 'EKS cluster name')
        string(name: 'K8S_NAMESPACE', defaultValue: 'library', description: 'Kubernetes namespace to deploy into')
    }

    environment {
        CATALOGUE_REPOSITORY = 'librarycatalogue'
        SHOP_REPOSITORY = 'libraryshop'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.IMAGE_TAG = sh(
                        script: 'git rev-parse HEAD | cut -c1-12',
                        returnStdout: true
                    ).trim() + "-${env.BUILD_NUMBER}"
                    env.ECR_REGISTRY = "${params.AWS_ACCOUNT_ID}.dkr.ecr.${params.AWS_REGION}.amazonaws.com"
                }
            }
        }

        stage('Maven build and tests') {
            steps {
                dir('librarycatalogue') {
                    sh 'chmod +x ./mvnw && ./mvnw -B clean verify'
                }
            }
        }

        stage('Build and push Docker images') {
            steps {
                sh '''
                    set -eu
                    aws ecr get-login-password --region "$AWS_REGION" \
                      | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                    docker build -t "$ECR_REGISTRY/$CATALOGUE_REPOSITORY:$IMAGE_TAG" ./librarycatalogue
                    docker build -t "$ECR_REGISTRY/$SHOP_REPOSITORY:$IMAGE_TAG" ./libraryshop
                    docker push "$ECR_REGISTRY/$CATALOGUE_REPOSITORY:$IMAGE_TAG"
                    docker push "$ECR_REGISTRY/$SHOP_REPOSITORY:$IMAGE_TAG"
                '''
            }
        }

        stage('Deploy to EKS') {
            steps {
                sh '''
                    set -eu
                    aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER"
                    kubectl create namespace "$K8S_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
                    kubectl apply -n "$K8S_NAMESPACE" \
                      -f kubernetes/librarydatabase/pvc.yaml \
                      -f kubernetes/librarydatabase/deployment.yaml \
                      -f kubernetes/librarydatabase/service.yaml \
                      -f kubernetes/librarycatalogue/deployment.yaml \
                      -f kubernetes/librarycatalogue/service.yaml \
                      -f kubernetes/libraryshop/deployment.yaml \
                      -f kubernetes/libraryshop/service.yaml
                    kubectl set image -n "$K8S_NAMESPACE" deployment/library-backend \
                      backend="$ECR_REGISTRY/$CATALOGUE_REPOSITORY:$IMAGE_TAG"
                    kubectl set image -n "$K8S_NAMESPACE" deployment/library-frontend \
                      frontend="$ECR_REGISTRY/$SHOP_REPOSITORY:$IMAGE_TAG"
                    kubectl rollout status -n "$K8S_NAMESPACE" deployment/mysql --timeout=5m
                    kubectl rollout status -n "$K8S_NAMESPACE" deployment/library-backend --timeout=5m
                    kubectl rollout status -n "$K8S_NAMESPACE" deployment/library-frontend --timeout=5m
                '''
            }
        }
    }
}
