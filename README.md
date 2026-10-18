# Library Management System

A small three-tier application for cataloging books. The browser talks to a Spring Boot REST API; the API stores records in MySQL.

## Architecture

```text
Browser
  |
  | HTTP :8081 locally / LoadBalancer :80 on Kubernetes
  v
Nginx frontend (HTML, CSS, JavaScript)
  |  /api/* proxied to backend:8080
  v
Spring Boot backend :8080
  |  JDBC using DB_HOST, DB_NAME, DB_USER, DB_PASSWORD
  v
MySQL 8 :3306  --->  named Docker volume / Kubernetes PVC
```

Only the frontend service is public in Kubernetes. The backend and MySQL services are internal ClusterIP services.

## Files at a glance

- `backend/pom.xml` declares Java 21, Spring Boot, validation, JPA, MySQL Connector/J, and test dependencies.
- `backend/mvnw`, `backend/mvnw.cmd`, `.mvn/wrapper/maven-wrapper.jar`, and `.mvn/wrapper/maven-wrapper.properties` provide the Maven Wrapper and its Maven distribution settings.
- `backend/src/main/java/com/example/library/LibraryApplication.java` starts Spring Boot; `HealthController.java` exposes `/healthz`.
- `backend/src/main/java/com/example/library/book/Book.java` maps persisted book fields; `BookStatus.java` defines `AVAILABLE` and `BORROWED`.
- `backend/src/main/java/com/example/library/book/BookRequest.java` validates incoming book data; `BookRepository.java` provides JPA persistence and ISBN checks.
- `backend/src/main/java/com/example/library/book/BookService.java` implements catalog search and CRUD; `BookController.java` exposes the REST endpoints.
- `backend/src/main/java/com/example/library/book/BookNotFoundException.java` and `DuplicateIsbnException.java` model expected API errors.
- `backend/src/main/java/com/example/library/ApiExceptionHandler.java` returns structured 400, 404, and 409 responses.
- `backend/src/main/resources/application.properties` builds the JDBC connection only from the four `DB_*` environment variables.
- `backend/src/test/java/com/example/library/book/BookServiceTest.java` tests service-layer search and CRUD behavior.
- `backend/Dockerfile` packages the built JAR in a single-stage Java 21 JRE image and runs it as UID 10001.
- `frontend/index.html` defines the accessible catalog and add/edit form; `frontend/styles.css` provides responsive styling.
- `frontend/app.js` calls the relative `/api/books` endpoint and handles search, validation, create, edit, and delete actions.
- `frontend/nginx.conf` serves static assets and proxies `/api/` to the configured backend URL; `frontend/Dockerfile` packages the site using `nginx:alpine`.
- `docker-compose.yml` runs the three services, waits for MySQL health, and retains database files in the `mysql-data` volume.
- `kubernetes/namespace.yaml` creates the default `library` namespace.
- `kubernetes/mysql/secret.yaml` is a credentials template; `pvc.yaml` requests persistent storage; `deployment.yaml` runs MySQL; `service.yaml` exposes it internally.
- `kubernetes/backend/deployment.yaml` configures secret-backed DB settings, probes, and resource bounds; `service.yaml` exposes the API internally.
- `kubernetes/frontend/deployment.yaml` configures Nginx and its backend URL; `service.yaml` is the only LoadBalancer entry point.
- `Jenkinsfile` tests the backend, builds and pushes immutable ECR tags, and deploys to EKS using the agent's AWS instance profile.
- `.gitignore` excludes build output, local environment files, and logs.
- `frontend/preview.html` displays the frontend with sample books and works without a running backend or database.

## Requirements

- Java 21 for the backend build and tests.
- Docker Desktop with Docker Compose for the local full-stack run.
- For Kubernetes: `kubectl`, an EKS cluster, AWS CLI, ECR repositories, a Linux EC2-backed Jenkins agent with Docker and Java 21, and an EKS access role for that instance profile.

The Maven Wrapper downloads Maven 3.9.9 on first use. The backend image expects the JAR to be built first.

## Run and test locally (Windows PowerShell)

From the `library-management` directory:

```powershell
cd backend
.\mvnw.cmd -B clean verify
cd ..
docker compose up --build -d
docker compose ps
```

Open <http://localhost:8081>. The example development credentials in Compose are for local use only. Override them before starting if desired:

To preview the frontend without Docker or a running backend, open `frontend/preview.html` directly in a browser. It uses in-memory sample books and does not save data; normal `index.html` continues to use the backend API.

```powershell
$env:MYSQL_USER = 'library_user'
$env:MYSQL_PASSWORD = 'a-local-development-password'
$env:MYSQL_ROOT_PASSWORD = 'a-different-local-root-password'
docker compose up --build -d
```

Test health and the full CRUD API:

```powershell
Invoke-RestMethod http://localhost:8081/healthz
$book = @{ title = 'Dune'; author = 'Frank Herbert'; isbn = '9780441172719'; publicationYear = 1965; status = 'AVAILABLE' } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/books -ContentType 'application/json' -Body $book
$created
Invoke-RestMethod "http://localhost:8081/api/books?search=Dune"
$updatedBook = @{ title = 'Dune'; author = 'Frank Herbert'; isbn = '9780441172719'; publicationYear = 1965; status = 'BORROWED' } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "http://localhost:8081/api/books/$($created.id)" -ContentType 'application/json' -Body $updatedBook
Invoke-RestMethod "http://localhost:8081/api/books/$($created.id)"
Invoke-RestMethod -Method Delete -Uri "http://localhost:8081/api/books/$($created.id)"
```

API routes: `GET /api/books?search=...`, `GET /api/books/{id}`, `POST /api/books`, `PUT /api/books/{id}`, and `DELETE /api/books/{id}`. ISBNs must contain 10 or 13 digits; years must be between 1000 and 9999; statuses are `AVAILABLE` or `BORROWED`.

Stop the stack with `docker compose down`. To also delete the local database volume and its data, run `docker compose down -v`.

## Deploy to Kubernetes / EKS

1. Create ECR repositories named `library-backend` and `library-frontend` in the target account and region. Ensure the EKS worker role can pull from those repositories and the Jenkins EC2 instance profile can push to ECR and update the target EKS cluster.
2. Provision a `mysql-credentials` Secret in the target namespace using your approved secret-management process. `kubernetes/mysql/secret.yaml` is an example template only; replace its values before manually applying it, and never commit real credentials. Kubernetes Secrets are base64-encoded, not encrypted in source control. Jenkins deliberately does not apply the template, so deployments won't overwrite a securely provisioned Secret. Ensure the EKS cluster has a default StorageClass for the PVC.
3. Jenkins needs Docker, AWS CLI, `kubectl`, Java 21, and network access to ECR/EKS. Configure the Jenkins agent's EC2 instance profile with the required permissions; do not put AWS keys in Jenkins or the repository.
4. Run this Jenkins pipeline and set `AWS_ACCOUNT_ID`, `AWS_REGION`, `EKS_CLUSTER`, and `K8S_NAMESPACE`. The namespace defaults to `library`. The pipeline uses the first 12 characters of the commit SHA plus the Jenkins build number as the image tag and never publishes `latest`.
5. Check rollout and retrieve the public frontend address:

```powershell
kubectl get pods,services -n library
kubectl get service library-frontend -n library
kubectl rollout status deployment/mysql -n library
kubectl rollout status deployment/library-backend -n library
kubectl rollout status deployment/library-frontend -n library
```

The LoadBalancer hostname/address can take several minutes to appear. The Jenkins pipeline creates the requested namespace dynamically; `kubernetes/namespace.yaml` is provided for manual deployments that use the default `library` namespace.

For a manual deployment, first apply the namespace, then apply a Secret with real credentials (for a non-production test, edit the template locally and apply it without committing the values), then the MySQL resources, then backend and frontend resources:

```powershell
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -n library -f kubernetes/mysql/secret.yaml
kubectl apply -n library -f kubernetes/mysql
kubectl rollout status deployment/mysql -n library --timeout=5m
kubectl apply -n library -f kubernetes/backend
kubectl apply -n library -f kubernetes/frontend
```

The manifests' `*-local` container images are placeholders for local clusters. For a remote cluster, push both images to ECR and use `kubectl set image` with those ECR image references, as the Jenkins pipeline does.

## Common failures

- **Docker command not found / daemon unavailable:** install and start Docker Desktop, then verify `docker version` and `docker compose version`.
- **Maven fails to connect to MySQL:** check `docker compose ps` and `docker compose logs mysql backend`; Compose starts the API only after the MySQL healthcheck passes. Verify `DB_*` names and credentials match.
- **Port 8081 is already in use:** change the host-side port in `docker-compose.yml` (the left side of `8081:80`) and browse to that port.
- **Duplicate ISBN / invalid request:** ISBN must be exactly 10 or 13 digits; required fields and year bounds are validated. The API returns field-specific validation errors and HTTP 409 for a duplicate ISBN.
- **PVC remains Pending:** configure a default StorageClass or set `storageClassName` on `kubernetes/mysql/pvc.yaml` to one supported by the cluster.
- **Kubernetes pods report `ImagePullBackOff`:** verify the image tag in the deployment, ECR repository/region, and EKS worker-role ECR pull permissions.
- **MySQL or backend pod fails to start:** check `kubectl describe pod` and `kubectl logs`; ensure the Secret exists, its keys match `DB_USER`, `DB_PASSWORD`, and `MYSQL_ROOT_PASSWORD`, and the credentials were changed before first database initialization.
- **EKS Jenkins deployment is unauthorized:** attach the needed ECR permissions to the EC2 instance profile and grant that IAM role access to the EKS cluster; verify the Jenkins agent has AWS CLI and `kubectl`.
- **LoadBalancer has no external address yet:** wait for cloud provisioning and check the Service events with `kubectl describe service library-frontend -n library`.
