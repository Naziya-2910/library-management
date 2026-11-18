# Library Management System: CI/CD on Amazon EKS with Jenkins, Docker and Argo CD

A three-tier application (Nginx UI, Spring Boot REST API, MySQL) deployed to Amazon EKS through a GitOps pipeline. Jenkins builds, tests, scans and pushes both images; Argo CD deploys whatever is committed to the `kubernetes` folder.

## Architecture

```
Developer
   |  git push
   v
GitHub (this repo)
   |
   v
Jenkins pipeline
   Maven build + tests (API) -> SonarQube -> Docker build (API + UI) -> Trivy scan -> push both images to ECR
   |
   v
Jenkins commits the new image tags to
   kubernetes/librarycatalogue/deployment.yaml
   kubernetes/libraryshop/deployment.yaml
   |
   v
Argo CD detects the commit and syncs the cluster
   |
   v
Amazon EKS (namespace: library)
   |
   +--> libraryshop (Nginx UI) --- LoadBalancer Service ---> Browser
   |         | /api/* and /healthz
   |         v
   +--> librarycatalogue (Spring Boot API)  [internal Service "backend"]
   |         | JDBC: DB_HOST, DB_NAME, DB_USER, DB_PASSWORD
   |         v
   +--> MySQL 8.4 (librarydatabase)  [internal Service "mysql" + 5 GiB persistent volume]
```

| Tool | Purpose |
| --- | --- |
| Jenkins | Runs the CI pipeline and writes the new image tags to Git |
| Docker | Builds the API and UI images |
| SonarQube + SonarScanner | Code quality and security analysis of the API |
| Trivy | Scans both images for vulnerabilities |
| Amazon ECR | Stores the two images (`librarycatalogue`, `libraryshop`) |
| Argo CD | Deploys the manifests in `kubernetes/` to the cluster |
| Amazon EKS | Runs the UI, API and MySQL |

## Repository structure

```
library-management/
  librarycatalogue/        Java 21 / Spring Boot REST API, tests, Maven Wrapper, Dockerfile
  libraryshop/             HTML, CSS, JavaScript, Nginx, UI Dockerfile
  kubernetes/
    namespace.yaml
    librarycatalogue/      API Deployment and internal Service (name: backend)
    libraryshop/           UI Deployment and public LoadBalancer Service (name: library-frontend)
    librarydatabase/       MySQL Deployment, Service (name: mysql), PVC, Secret template
  Jenkinsfile
  README.md
```

How this differs from a single-image app: two ECR repositories, two images tagged per build, two manifests updated per build, a database that needs a Secret and a persistent volume, and an Argo CD Application that must read subfolders.

## Order of work

1. Prerequisites and ECR repositories (section 1)
2. Install tools on the Jenkins server (section 2)
3. Connect kubectl and install Argo CD (section 3)
4. Configure Jenkins and create the pipeline job (sections 4 and 5)
5. Run the pipeline until it is green (section 6.1)
6. Prepare the cluster: namespace, Secret, storage class, subnet tags, public Service (sections 6.2 to 6.6)
7. Create the Argo CD Application (section 6.7)
8. Check the pods and open the application (sections 6.8 and 7)

## Values used in this guide

Set these in every new terminal session:

```bash
export AWS_REGION=ap-south-1
export CLUSTER_NAME=my-eks
export NAMESPACE=library
```

Whenever a command contains text in `<angle brackets>`, replace it with your real value and remove the brackets. Never paste YAML straight into the terminal; YAML goes in files.

## 1. Prerequisites

- An AWS account and an IAM user or role allowed to use ECR, EKS and EC2.
- An Ubuntu EC2 instance for Jenkins and SonarQube, with several GB of RAM and at least 20 GB of disk. Open ports 8080 (Jenkins) and 9000 (SonarQube) only to your own IP.
- An Amazon EKS cluster in the same region (EKS Auto Mode is used here).
- A GitHub personal access token with write access to this repo.

Create the two ECR repositories. The pipeline fails with `repository does not exist` without them:

```bash
aws ecr create-repository --repository-name librarycatalogue --image-tag-mutability IMMUTABLE --region $AWS_REGION
aws ecr create-repository --repository-name libraryshop --image-tag-mutability IMMUTABLE --region $AWS_REGION
```

Immutable tags are safe here because every build pushes a new unique tag.

## 2. Install the tools on the Jenkins EC2 instance

If you already built this server for another project, skip to section 3.

**Jenkins:** install it from https://www.jenkins.io/doc/book/installing/linux/#debianubuntu

**Docker** (Jenkins builds images with it):

```bash
sudo apt-get update
sudo apt install -y docker.io
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
```

**SonarQube** (container; create a token after first login):

```bash
docker run -d --name sonarqube --restart unless-stopped -p 9000:9000 sonarqube:latest
```

**SonarScanner CLI:**

```bash
sudo apt update
sudo apt install -y curl unzip openjdk-21-jre
cd /tmp
curl -fSL https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-8.2.0.6438-linux-x64.zip -o sonar-scanner.zip
sudo unzip -o sonar-scanner.zip -d /opt
sudo ln -sfn /opt/sonar-scanner-8.2.0.6438-linux-x64 /opt/sonar-scanner
echo 'export PATH="/opt/sonar-scanner/bin:$PATH"' | sudo tee /etc/profile.d/sonar-scanner.sh
source /etc/profile.d/sonar-scanner.sh
sudo -u jenkins /opt/sonar-scanner/bin/sonar-scanner --version
```

**Trivy:**

```bash
sudo apt-get update
sudo apt-get install -y wget gnupg lsb-release
sudo mkdir -p /etc/apt/keyrings
wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | gpg --dearmor | sudo tee /etc/apt/keyrings/trivy.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/trivy.list
sudo apt-get update
sudo apt-get install -y trivy
```

**AWS CLI v2:**

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install
aws configure
```

**kubectl:**

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo mkdir -p -m 755 /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.37/deb/Release.key | sudo gpg --dearmor --yes -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
sudo chmod 644 /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.37/deb/ /' | sudo tee /etc/apt/sources.list.d/kubernetes.list
sudo apt-get update
sudo apt-get install -y kubectl
```

**Helm 3** (installs Argo CD):

```bash
curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
chmod 700 get_helm.sh
./get_helm.sh
```

## 3. Connect kubectl to EKS and install Argo CD

```bash
aws eks update-kubeconfig --region $AWS_REGION --name $CLUSTER_NAME
kubectl get nodes
aws eks describe-cluster --name $CLUSTER_NAME --region $AWS_REGION --query 'cluster.computeConfig.enabled'
```

The last command prints `true` for EKS Auto Mode, which changes the load balancer and storage setup below.

Add the Argo repository first, then install:

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update
helm upgrade --install argocd argo/argo-cd --namespace argocd --create-namespace
kubectl get pods -n argocd
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
```

Wait until the Argo CD pods are `Running`. The password command prints the first admin password (username `admin`).

To open the Argo CD web page, run the port-forward in a **second terminal** and leave it running, because it blocks that terminal until you press `Ctrl+C`:

```bash
kubectl port-forward --address 0.0.0.0 \
  svc/argocd-server -n argocd 8081:443
```

Open `https://<ec2-public-ip>:8081` in your browser. Allow inbound TCP 8081 in the instance's security group for your own IP only. Chrome warns "Your connection is not private" because of a self-signed certificate; click **Advanced**, then **Proceed**. The web page is optional; all steps below work with `kubectl`.

## 4. Configure Jenkins

1. **Plugins:** Pipeline, Git, SonarQube Scanner, CloudBees AWS Credentials, JUnit.
2. **SonarQube token:** log in to `http://<sonarqube-host>:9000`, open **My Account, Security**, and generate a token. In Jenkins add it as a **Secret text** credential with ID `sonar-token`.
3. **SonarQube server:** Manage Jenkins, System, **SonarQube servers**. Name it exactly `SonarQubeServer`, set the URL, select `sonar-token`. A `401 Unauthorized` in the Sonar stage means this is missing or wrong.
4. **Credentials:**
   - `git-cred`: GitHub username plus a personal access token with write access.
   - `aws-cred`: AWS access key and secret for the account that owns the ECR repositories.
5. **Pipeline job:** Definition **Pipeline script from SCM**, Git, repository `https://github.com/Naziya-2910/library-management.git`, branch `main`, credentials `git-cred`, script path `Jenkinsfile`.

## 5. The pipeline

The `Jenkinsfile` in the repo root runs these stages:

| Stage | What it does |
| --- | --- |
| Checkout | Clones `main` from GitHub |
| Set Image Tag | One tag for both images: `<build number>-<short commit hash>` |
| Maven Build and Test | Runs `mvn clean verify` inside `librarycatalogue/`; a failing test stops the build |
| SonarQube Analysis | Analyses the API source |
| Build Docker Images | Builds `librarycatalogue` and `libraryshop` |
| Image Scan | Saves Trivy reports, then checks for fixable CRITICAL vulnerabilities in either image (see the Trivy gate below) |
| Push to ECR | Logs in to ECR and pushes both images |
| Update GitOps Repo | Writes the new tag into both deployment manifests, commits as Jenkins with `[skip ci]`, and pushes to `main` |

Why the last stage exists: Argo CD only deploys what is in Git. The commit that changes the image tags is what triggers each deployment, and it gives you release history and rollback by reverting a commit.

Each deployment manifest must contain exactly one `image:` line, because the pipeline replaces every `image:` line in the file.

### The Trivy gate

The `Jenkinsfile` has this line in its `environment` block:

```groovy
TRIVY_EXIT_CODE = '0'
```

- `'0'`: Trivy lists the CRITICAL findings and saves the reports, but the build continues. Use this for learning and testing.
- `'1'`: any fixable CRITICAL finding fails the build, and the push and deploy stages are skipped. Use this for real use.

The API image can fail this gate because of the Spring Boot version in `librarycatalogue/pom.xml` (for example `tomcat-embed-core` and `spring-webmvc` inside `app.jar`). The proper fix is to raise the Spring Boot version in the `<parent>` block of `pom.xml` to a current release, then set `TRIVY_EXIT_CODE` back to `'1'`.

## 6. Deploy to the cluster

### 6.1 Confirm the pipeline is green

In Jenkins, all stages must be green. Then check that both images are in ECR, and that GitHub has a new commit named `Update image tags to ... [skip ci]` on `main`:

```bash
aws ecr describe-images --repository-name librarycatalogue --region $AWS_REGION --query 'imageDetails[].imageTags' --output text
aws ecr describe-images --repository-name libraryshop --region $AWS_REGION --query 'imageDetails[].imageTags' --output text
```

Do not create the Argo CD Application before this succeeds. Until the pipeline has run, the deployment files have no real image tag and pods fail with `ImagePullBackOff`.

### 6.2 Check `kubernetes/namespace.yaml`

It must hold the real name, not a placeholder. Kubernetes rejects uppercase names, and Argo CD shows `OutOfSync` otherwise:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: library
```

### 6.3 Create the namespace and the database Secret

The API and MySQL both read the Secret `mysql-credentials`. Do not apply `kubernetes/librarydatabase/secret.yaml`; it is a placeholder template.

```bash
kubectl create namespace $NAMESPACE
```

If this prints `AlreadyExists`, that is fine; go on. Then check the Secret:

```bash
kubectl get secret mysql-credentials -n $NAMESPACE
```

If it says `NotFound`, create it with your own passwords:

```bash
kubectl create secret generic mysql-credentials -n $NAMESPACE \
  --from-literal=DB_USER=library_user \
  --from-literal=DB_PASSWORD='choose-a-password' \
  --from-literal=MYSQL_ROOT_PASSWORD='choose-another-password'
```

The keys must be exactly `DB_USER`, `DB_PASSWORD` and `MYSQL_ROOT_PASSWORD`. Confirm them with:

```bash
kubectl describe secret mysql-credentials -n $NAMESPACE
```

Also check for leftovers from an earlier attempt. An old database volume keeps its old passwords:

```bash
kubectl get all,pvc -n $NAMESPACE
```

You want `No resources found`. If old pods or a `mysql-data` volume are listed, delete them before continuing.

### 6.4 Storage class for the database volume (EKS Auto Mode)

MySQL asks for a 5 GiB volume (`mysql-data`). If the cluster has no default StorageClass, the pod stays `Pending`. Create one:

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: auto-ebs-sc
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.eks.amazonaws.com
volumeBindingMode: WaitForFirstConsumer
parameters:
  type: gp3
  encrypted: "true"
EOF
```

### 6.5 Tag the subnets for the load balancer

Tag the cluster's public subnets so the load balancer can be placed in them:

```bash
SUBNETS=$(aws eks describe-cluster --name $CLUSTER_NAME --region $AWS_REGION --query 'cluster.resourcesVpcConfig.subnetIds' --output text)
aws ec2 create-tags --region $AWS_REGION --resources $SUBNETS \
  --tags Key=kubernetes.io/role/elb,Value=1 Key=kubernetes.io/cluster/$CLUSTER_NAME,Value=shared
aws ec2 describe-subnets --region $AWS_REGION --subnet-ids $SUBNETS --query 'Subnets[].{ID:SubnetId,Tags:Tags}' --output json | cat
```

The tags are per cluster, so repeat this for any new cluster. If your cluster also has private subnets, tag only the public ones, otherwise the internet-facing load balancer can end up in a private subnet and be unreachable.

### 6.6 Make the UI Service public

Only the UI Service is public. Replace the whole content of `kubernetes/libraryshop/service.yaml` on GitHub with the following and commit it to `main`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: library-frontend
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing
spec:
  type: LoadBalancer
  loadBalancerClass: eks.amazonaws.com/nlb
  selector:
    app: library-frontend
  ports:
    - name: http
      port: 80
      targetPort: http
```

`loadBalancerClass` hands the Service to EKS Auto Mode, and the annotation makes the load balancer internet-facing (the default is internal and cannot be opened from a browser).

The other two Services do not change: `kubernetes/librarycatalogue/service.yaml` (name `backend`, ClusterIP) and `kubernetes/librarydatabase/service.yaml` (name `mysql`, ClusterIP). Do not rename `backend`, because the UI's Nginx proxies `/api/*` to `http://backend:8080`.

Do this step before creating the Argo CD Application. `loadBalancerClass` cannot be changed on an existing Service, so if the Service was already created without it, delete it after committing and let Argo CD recreate it:

```bash
kubectl delete service library-frontend -n $NAMESPACE
```

### 6.7 Create the Argo CD Application

The `kubernetes` folder has subfolders, so the Application needs `recurse`, and it must exclude the placeholder Secret so Argo CD does not overwrite the real one:

```bash
cat > library-app.yaml <<'EOF'
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: library-management
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/Naziya-2910/library-management.git
    targetRevision: main
    path: kubernetes
    directory:
      recurse: true
      exclude: '**/secret.yaml'
  destination:
    server: https://kubernetes.default.svc
    namespace: library
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
EOF
kubectl apply -f library-app.yaml
```

The Application tells Argo CD which repo, branch and folder to watch and where to deploy. Without it Argo CD does nothing. If the repository is private, add its credentials in Argo CD first (Settings, Repositories).

### 6.8 Check the deployment

```bash
kubectl get application library-management -n argocd
kubectl get pods,svc,pvc -n library
```

You want `mysql`, `library-backend` and `library-frontend` all `Running`, the `mysql-data` volume `Bound`, and `Synced` / `Healthy` in Argo CD. The API may restart a few times while MySQL starts; this is normal if it settles.

Check that each Service has a pod behind it:

```bash
kubectl get svc,endpoints -n library
```

Every Service must show a pod address under `ENDPOINTS`. `<none>` means the Service selector or port name does not match the deployment.

## 7. Open the application

Get the load balancer DNS name:

```bash
kubectl get service library-frontend -n library
kubectl get service library-frontend -n library -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}'
```

The name ends in `.elb.amazonaws.com`. If `EXTERNAL-IP` shows `<pending>`, wait 2 to 5 minutes, then check:

```bash
kubectl describe service library-frontend -n library | tail -8
```

You want a `SuccessfullyReconciled` event. Then confirm the load balancer is public and active:

```bash
aws elbv2 describe-load-balancers --region $AWS_REGION --query 'LoadBalancers[?contains(LoadBalancerName,`k8s-library`)].{Name:LoadBalancerName,Scheme:Scheme,State:State.Code}' --output table
```

`Scheme` must be `internet-facing` and `State` must be `active`. Open `http://<hostname>/`. Use `http`, not `https`.

Check the API through the UI proxy:

```bash
curl http://<hostname>/healthz
curl "http://<hostname>/api/books?search=Dune"
```

Create a book:

```bash
curl -X POST http://<hostname>/api/books -H 'Content-Type: application/json' \
  -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441172719","publicationYear":1965,"status":"AVAILABLE"}'
```

ISBNs must have 10 or 13 digits, years 1000 to 9999, and status `AVAILABLE` or `BORROWED`. A duplicate ISBN returns HTTP 409.

Alternative without a load balancer: `kubectl port-forward --address 0.0.0.0 svc/library-frontend -n library 8082:80`, then open `http://<ec2-public-ip>:8082/` (allow port 8082 in the instance's security group for your IP).

## 8. Test the whole flow

1. On GitHub, change a visible line in `libraryshop/` (for example the page title) and commit to `main`. Do not edit the deployment manifests; Jenkins changes them.
2. Click **Build Now** in Jenkins.
3. When it finishes, both deployment files show a new Jenkins commit like `Update image tags to 3-abc1234 [skip ci]`.
4. Argo CD rolls out within about 3 minutes. To speed it up:
   ```bash
   kubectl annotate application library-management -n argocd argocd.argoproj.io/refresh=hard --overwrite
   ```
5. Confirm the new images are running:
   ```bash
   kubectl get pods -n library
   kubectl get deployment -n library -o wide
   ```
6. Reload the page with `Ctrl+Shift+R`.

Books are stored in MySQL on the persistent volume, so they survive pod restarts. Restart the API pod and check that your books are still there.

## 9. Optional: use Amazon RDS instead of in-cluster MySQL

The API only reads `DB_HOST`, `DB_NAME`, `DB_USER` and `DB_PASSWORD`, so no code change is needed:

1. Create a MySQL RDS instance in the same VPC as the cluster, with a database named `library`.
2. Allow port 3306 from the cluster nodes' security group.
3. Set `DB_HOST` in the API deployment to the RDS endpoint, and put the RDS user and password in `mysql-credentials`.
4. Remove `kubernetes/librarydatabase/` (or its Deployment, Service and PVC) from Git. With `prune: true` Argo CD deletes them.

Hibernate (`ddl-auto=update`) creates the tables. RDS charges while it runs, even when idle.

## 10. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `bash: syntax error near unexpected token 'newline'` | A `<placeholder>` was typed literally | Replace it, brackets included, with the real value |
| `apiVersion:: command not found` | YAML pasted into the terminal | Put YAML in a file or on GitHub |
| `namespaces "library" already exists` | Namespace created earlier | Harmless; skip that command |
| `secrets "mysql-credentials" not found` | Secret not created yet | Create it (section 6.3) |
| Jenkins Sonar stage: `HTTP 401 Unauthorized` | No valid SonarQube token | Create a token, store it as `sonar-token`, set it on `SonarQubeServer` |
| Jenkins push fails: `repository does not exist` | An ECR repository is missing in this account or region | Create both repositories (section 1) |
| Build shows FAILURE at Image Scan, later stages skipped | `TRIVY_EXIT_CODE` is `'1'` and an image has a fixable CRITICAL vulnerability | Upgrade the dependency or base image, or set `TRIVY_EXIT_CODE = '0'` for testing; reports are archived as build artifacts |
| `No such image ...amazonaws.com/...` in the post step | Cleanup looked for images that were never tagged because the push was skipped | Harmless |
| Pod `ImagePullBackOff`, event `not found` | Tag not in ECR, or no tag in the manifest | Run the pipeline; check both repositories have the tag |
| Pod `ImagePullBackOff`, event `denied` | Node role cannot pull from ECR | Attach `AmazonEC2ContainerRegistryPullOnly` to the node role |
| MySQL pod `Pending`, PVC `Pending` | No default StorageClass | Create it (section 6.4) |
| API `CrashLoopBackOff` | Cannot reach MySQL, or Secret missing or wrong keys | Check `kubectl logs deployment/library-backend -n library`, the `mysql` Service, and the Secret keys |
| Service shows `ENDPOINTS` `<none>` | Selector or port name does not match the deployment | Compare `selector` and `targetPort` with the deployment's labels and container port names |
| Argo CD overwrote the Secret | `secret.yaml` was not excluded | Add `exclude: '**/secret.yaml'` and recreate the Secret |
| Argo CD `OutOfSync`: `Namespace "NAMESPACE" is invalid` | Placeholder in `namespace.yaml` | Set `name: library` |
| Service `EXTERNAL-IP` `<pending>`, `Evaluated 0 subnets` | Subnets lack load balancer tags | Tag them (section 6.5) |
| Still `<pending>` on EKS Auto Mode | Missing `loadBalancerClass` or annotation | Fix `service.yaml`, delete the Service, refresh |
| Browser `ERR_CONNECTION_REFUSED` | `https://` used, load balancer still starting, or no healthy pod | Use `http://`, wait, check pods |
| Browser `ERR_TIMED_OUT` | Load balancer is `internal`, still provisioning, has no healthy targets, or your own network blocks port 80 | Run the checks below |

### Checks for `ERR_TIMED_OUT`

```bash
# 1. Scheme must be internet-facing and State active
aws elbv2 describe-load-balancers --region $AWS_REGION --query 'LoadBalancers[?contains(LoadBalancerName,`k8s-library`)].{Scheme:Scheme,State:State.Code}' --output table

# 2. The frontend pod must be Running, with an endpoint
kubectl get pods -n library
kubectl get endpoints library-frontend -n library

# 3. Targets must be healthy
TG=$(aws elbv2 describe-target-groups --region $AWS_REGION --query 'TargetGroups[?contains(TargetGroupName,`k8s-library`)].TargetGroupArn' --output text)
aws elbv2 describe-target-health --region $AWS_REGION --target-group-arn $TG --output table

# 4. Test from the Jenkins server (replace the hostname)
curl -m 10 -I http://<hostname>/
```

- `Scheme` is `internal`: fix `service.yaml` (section 6.6), delete the Service, let Argo CD recreate it.
- `State` is `provisioning`: wait 3 to 5 minutes.
- Targets `unhealthy` or `initial` for a long time: check the frontend pod and the node security group.
- `curl` works on the server but not in your browser: your own network or firewall may block port 80; try another network or a phone hotspot.

To see why an Argo CD sync fails:

```bash
kubectl get application library-management -n argocd -o jsonpath='{range .status.resources[*]}{.kind}{"\t"}{.name}{"\t"}{.status}{"\t"}{.health.status}{"\n"}{end}'
kubectl get application library-management -n argocd -o jsonpath='{.status.operationState.phase}{"\n"}{.status.operationState.message}{"\n"}'
```

## 11. Security notes

- Never commit tokens, AWS keys or passwords. Keep them in Jenkins credentials and Kubernetes Secrets.
- Kubernetes Secret values committed to Git are not secure; create `mysql-credentials` by hand or use a secrets manager integration.
- Restrict ports 8080 (Jenkins), 8081 (Argo CD port-forward), 9000 (SonarQube) and any NodePort to your own IP.
- The pipeline pushes to `main`; use a token limited to this repository.
- Do not leave `TRIVY_EXIT_CODE = '0'` on a real deployment: images with known critical vulnerabilities would be pushed and deployed.
- If SonarQube's public IP changes after a restart, update the server URL in Jenkins, or give the instance an Elastic IP.

## 12. Cleanup

EKS, nodes, load balancers, EBS volumes and ECR images all cost money while they exist.

```bash
kubectl delete application library-management -n argocd
```

With `prune: true` this removes the workloads and the Service, which releases the load balancer. The MySQL volume may remain; delete the PVC to remove the data:

```bash
kubectl delete pvc mysql-data -n library
```

Delete the rest by hand in AWS when finished: the EKS cluster, the Jenkins EC2 instance, both ECR repositories and the subnet tags.
## Troubleshooting

- **API cannot connect to MySQL:** check that the `mysql` Service is ready and that its Secret keys match `DB_USER`, `DB_PASSWORD`, and `MYSQL_ROOT_PASSWORD`.
- **PVC remains Pending:** ensure the cluster has a suitable StorageClass for the 5 GiB `mysql-data` claim.
- **Pods show `ImagePullBackOff`:** verify the ECR image names, tag, region, and EKS worker role's ECR pull permissions.
- **Jenkins cannot push or deploy:** verify the Jenkins agent role's ECR permissions and EKS cluster access, plus the availability of Docker, AWS CLI, Java 21, and `kubectl`.
- **LoadBalancer has no external address:** allow time for provisioning and inspect the Service events; applying a `LoadBalancer` Service in AWS may incur charges.
- **Duplicate ISBN or invalid request:** ISBN must be exactly 10 or 13 digits. The API returns field-specific validation errors and HTTP 409 for a duplicate ISBN.
