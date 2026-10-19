# Library Management System

A three-tier application for managing a book catalogue. The browser UI calls a Spring Boot REST API, which stores books in MySQL.

## Architecture and existing features

```text
Browser :8081
  |
  v
Nginx UI (libraryshop) :80
  |  /api/* and /healthz
  v
Spring Boot API (librarycatalogue) :8080
  |  JDBC using DB_HOST, DB_NAME, DB_USER, DB_PASSWORD
  v
MySQL 8.4 (librarydatabase) :3306
```

The UI is the only public Kubernetes Service. The API and MySQL Services are internal. The API currently implements book create, read, update, delete, and search; validates ISBN, title, author, year, and status; and stores the `AVAILABLE` or `BORROWED` status on each book. There is no separate inventory/stock module or service, so the existing availability feature remains part of the catalogue.

The `Book` JPA entity maps to the `books` table and has no relationships to other entities. There are no SQL schema scripts: Hibernate's existing `spring.jpa.hibernate.ddl-auto=update` setting manages the schema. This reorganization leaves that behavior unchanged.

## Project layout

```text
library-management/
  librarycatalogue/                 # Java 21 / Spring Boot REST API, tests, Maven Wrapper
  libraryshop/                      # HTML, CSS, JavaScript, Nginx, UI Dockerfile
  kubernetes/
    namespace.yaml
    librarycatalogue/               # API Deployment and internal Service
    libraryshop/                    # UI Deployment and public LoadBalancer Service
    librarydatabase/                # MySQL Deployment, Service, PVC, Secret template
  Jenkinsfile
  README.md
```

The existing MySQL Deployment, Service, PVC, and Secret name are retained so applying the new paths does not replace the database claim or its data. The MySQL image is the upstream `mysql:8.4` image; it does not need a custom Dockerfile. `librarydatabase` contains Kubernetes configuration, not a separate application or SQL schema.

This repository does not include a `docker-compose.yml`. Build and start each image with the individual Docker commands below.

## Requirements

- Java 21 to build and test the API.
- Docker Desktop (or another Docker Engine) to build and run images.
- For EKS: AWS CLI, `kubectl`, an existing EKS cluster and ECR repositories, and IAM permissions for image push/pull and cluster access.
- For Jenkins: a Linux agent with Java 21, Docker, AWS CLI, `kubectl`, and AWS role-based access. Do not put AWS access keys in the repository or pipeline.

The Maven Wrapper downloads Maven 3.9.9 when needed. Build the API JAR before building its Docker image.

## Build and test the API

From the repository root in Windows PowerShell:

```powershell
Push-Location .\librarycatalogue
.\mvnw.cmd -B clean verify
Pop-Location
```

## Build individual Docker images

From the repository root:

```powershell
docker build -t librarycatalogue:local .\librarycatalogue
docker build -t libraryshop:local .\libraryshop
```

The API image uses the verified JAR and runs as a non-root user. The UI image contains the static site and Nginx. MySQL is a separate upstream image.

## Run the components with Docker

The API needs a reachable MySQL database. The following example creates an isolated Docker network and persistent named volume, then starts MySQL, the API, and the UI independently. The volume is deliberately not removed by the cleanup commands below.

Enter local-only database credentials into the current PowerShell process; do not put real credentials in scripts or source control:

```powershell
$env:MYSQL_USER = 'library_user'
$mysqlPassword = Read-Host 'Local MySQL password' -AsSecureString
$rootPassword = Read-Host 'Local MySQL root password' -AsSecureString
$env:MYSQL_PASSWORD = [System.Net.NetworkCredential]::new('', $mysqlPassword).Password
$env:MYSQL_ROOT_PASSWORD = [System.Net.NetworkCredential]::new('', $rootPassword).Password

docker network create library-network
docker volume create librarydatabase-data
docker run --name mysql --network library-network --network-alias mysql `
  -e MYSQL_DATABASE=library -e MYSQL_USER -e MYSQL_PASSWORD -e MYSQL_ROOT_PASSWORD `
  -v librarydatabase-data:/var/lib/mysql -d mysql:8.4
```

Wait for MySQL to finish initialization before starting the API:

```powershell
docker build -t librarycatalogue:local .\librarycatalogue
docker run --name backend --network library-network -p 8080:8080 `
  -e DB_HOST=mysql -e DB_NAME=library -e DB_USER=$env:MYSQL_USER -e DB_PASSWORD=$env:MYSQL_PASSWORD `
  -d librarycatalogue:local

docker build -t libraryshop:local .\libraryshop
docker run --name libraryshop --network library-network -p 8081:80 `
  -e BACKEND_URL=http://backend:8080 -d libraryshop:local
```

The `docker run -d` commands print each container ID. To view startup and runtime output, run `docker logs -f backend` or substitute `mysql` or `libraryshop`; press Ctrl+C to stop following logs (the container keeps running).

Open <http://localhost:8081> to use the UI. It proxies `/api/*` and `/healthz` to the API. The following PowerShell commands print the health response and CRUD API results:

```powershell
Invoke-RestMethod http://localhost:8081/healthz
$book = @{ title = 'Dune'; author = 'Frank Herbert'; isbn = '9780441172719'; publicationYear = 1965; status = 'AVAILABLE' } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/books -ContentType 'application/json' -Body $book
Invoke-RestMethod "http://localhost:8081/api/books?search=Dune"
$updatedBook = @{ title = 'Dune'; author = 'Frank Herbert'; isbn = '9780441172719'; publicationYear = 1965; status = 'BORROWED' } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "http://localhost:8081/api/books/$($created.id)" -ContentType 'application/json' -Body $updatedBook
Invoke-RestMethod "http://localhost:8081/api/books/$($created.id)"
Invoke-RestMethod -Method Delete -Uri "http://localhost:8081/api/books/$($created.id)"
```

API routes: `GET /api/books?search=...`, `GET /api/books/{id}`, `POST /api/books`, `PUT /api/books/{id}`, and `DELETE /api/books/{id}`. ISBNs must contain 10 or 13 digits; years must be between 1000 and 9999; statuses are `AVAILABLE` or `BORROWED`.

To stop only these containers without deleting database data:

```powershell
docker stop libraryshop backend mysql
docker rm libraryshop backend mysql
docker network rm library-network
```

The `librarydatabase-data` Docker volume remains in place after cleanup. Do not remove it unless you explicitly intend to delete the local database.

To preview the UI without Docker or a running API, open `libraryshop/preview.html` directly in a browser. It displays in-memory sample books and does not save changes.

## Push independent images to Amazon ECR

Create the two ECR repositories once (in the AWS account and region you intend to use). These commands create AWS resources; run them only when you are ready. Repository creation and EKS deployment are not performed by this project setup.

```powershell
$env:AWS_ACCOUNT_ID = '<12-digit-account-id>'
$env:AWS_REGION = 'us-east-1'
$env:IMAGE_TAG = '1.0.0'
$ecrRegistry = "$env:AWS_ACCOUNT_ID.dkr.ecr.$env:AWS_REGION.amazonaws.com"

aws ecr create-repository --repository-name librarycatalogue --image-tag-mutability IMMUTABLE --region $env:AWS_REGION
aws ecr create-repository --repository-name libraryshop --image-tag-mutability IMMUTABLE --region $env:AWS_REGION
aws ecr get-login-password --region $env:AWS_REGION | docker login --username AWS --password-stdin $ecrRegistry
```

Build, tag, and push each image separately:

```powershell
docker build -t "librarycatalogue:$env:IMAGE_TAG" .\librarycatalogue
docker tag "librarycatalogue:$env:IMAGE_TAG" "$ecrRegistry/librarycatalogue:$env:IMAGE_TAG"
docker push "$ecrRegistry/librarycatalogue:$env:IMAGE_TAG"

docker build -t "libraryshop:$env:IMAGE_TAG" .\libraryshop
docker tag "libraryshop:$env:IMAGE_TAG" "$ecrRegistry/libraryshop:$env:IMAGE_TAG"
docker push "$ecrRegistry/libraryshop:$env:IMAGE_TAG"
```

The Jenkins pipeline runs the same independent image builds and pushes with a tag based on the Git commit and Jenkins build number. Configure Jenkins with access to the ECR repositories and EKS cluster through its AWS role; do not store AWS keys in the Jenkinsfile.

## Deploy to Kubernetes / EKS

The manifests target the existing `library` namespace by default. They use the stable internal DNS name `mysql` for database access, a persistent volume claim named `mysql-data`, and a Kubernetes Secret named `mysql-credentials` for database credentials. Required Secret keys are `DB_USER`, `DB_PASSWORD`, and `MYSQL_ROOT_PASSWORD`.

Provision that Secret through your approved secret-management process before deploying the database and API. Do not apply `kubernetes/librarydatabase/secret.yaml` as-is: it is a placeholder template, not a credentials store. Kubernetes Secret values committed in manifests are not secure; for EKS, use an approved Secrets Manager/Secrets Store integration or another controlled provisioning process. The Jenkins pipeline intentionally does not apply the placeholder Secret.

The PVC needs a suitable StorageClass in the cluster. The UI Service is `LoadBalancer`; applying it to EKS may provision a chargeable AWS load balancer. The commands below are examples for an explicitly approved deployment; they have not been run.

After ECR images exist and the Secret is provisioned, set the target cluster and namespace:

```powershell
$env:EKS_CLUSTER = '<existing-eks-cluster-name>'
$env:K8S_NAMESPACE = 'library'
$env:IMAGE_TAG = '<tag-pushed-to-ecr>'
aws eks update-kubeconfig --region $env:AWS_REGION --name $env:EKS_CLUSTER
kubectl create namespace $env:K8S_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
```

Apply the database and application resources:

```powershell
kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/librarydatabase/pvc.yaml
kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/librarydatabase/deployment.yaml
kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/librarydatabase/service.yaml
kubectl rollout status -n $env:K8S_NAMESPACE deployment/mysql --timeout=5m

kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/librarycatalogue/deployment.yaml
kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/librarycatalogue/service.yaml
kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/libraryshop/deployment.yaml
kubectl apply -n $env:K8S_NAMESPACE -f kubernetes/libraryshop/service.yaml

$ecrRegistry = "$env:AWS_ACCOUNT_ID.dkr.ecr.$env:AWS_REGION.amazonaws.com"
kubectl set image -n $env:K8S_NAMESPACE deployment/library-backend `
  "backend=$ecrRegistry/librarycatalogue:$env:IMAGE_TAG"
kubectl set image -n $env:K8S_NAMESPACE deployment/library-frontend `
  "frontend=$ecrRegistry/libraryshop:$env:IMAGE_TAG"
kubectl rollout status -n $env:K8S_NAMESPACE deployment/library-backend --timeout=5m
kubectl rollout status -n $env:K8S_NAMESPACE deployment/library-frontend --timeout=5m
kubectl get pods,services -n $env:K8S_NAMESPACE
```

The existing Kubernetes Deployment and Service names are retained to avoid unnecessary workload replacement; only their manifest folders and default image placeholders changed. The Jenkinsfile follows the same resource and Secret conventions. Check the `library-frontend` Service address with `kubectl get service library-frontend -n $env:K8S_NAMESPACE` after the load balancer is provisioned.

## Troubleshooting

- **API cannot connect to MySQL:** check that the `mysql` Service is ready and that its Secret keys match `DB_USER`, `DB_PASSWORD`, and `MYSQL_ROOT_PASSWORD`.
- **PVC remains Pending:** ensure the cluster has a suitable StorageClass for the 5 GiB `mysql-data` claim.
- **Pods show `ImagePullBackOff`:** verify the ECR image names, tag, region, and EKS worker role's ECR pull permissions.
- **Jenkins cannot push or deploy:** verify the Jenkins agent role's ECR permissions and EKS cluster access, plus the availability of Docker, AWS CLI, Java 21, and `kubectl`.
- **LoadBalancer has no external address:** allow time for provisioning and inspect the Service events; applying a `LoadBalancer` Service in AWS may incur charges.
- **Duplicate ISBN or invalid request:** ISBN must be exactly 10 or 13 digits. The API returns field-specific validation errors and HTTP 409 for a duplicate ISBN.
