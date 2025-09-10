# 3-tier-java-app

repo/
├─ app/pom.xml
├─ app/src/main/java/com/example/demo/DemoApplication.java
├─ app/src/main/java/com/example/demo/HelloController.java
├─ Dockerfile               # multistage for app
├─ web/Dockerfile           # nginx base image
├─ web/nginx.conf
├─ web/index.html
├─ k8s/namespace.yaml
├─ k8s/mysql-secret.yaml
├─ k8s/mysql-svc.yaml
├─ k8s/mysql-statefulset.yaml
├─ k8s/app-deployment.yaml
├─ k8s/app-service.yaml
├─ k8s/app-hpa.yaml
├─ k8s/web-deployment.yaml
└─ k8s/web-service.yaml
## 0) Prerequisites (once)

AWS account, CLI v2 logged in (aws configure), permissions for ECR and EKS.

Tools: git, docker, kubectl, eksctl, helm, Java 17, Maven.

Choose a region (example ap-south-1) and names:
export AWS_REGION=ap-south-1
export APP_NAME=hello-java
export ECR_NAME=$APP_NAME
export CLUSTER=demo-eks
export NS=demo
# Create ECR repo:
aws ecr create-repository --repository-name $ECR_NAME --region $AWS_REGION
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_URI=$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_NAME

# Build & test locally:

docker build -t $APP_NAME:dev .
docker run -p 8080:8080 $APP_NAME:dev
# hit http://localhost:8080 and /healthz

# If you must use a “webserver base image”: add this file as Dockerfile.nginx (1-tier static app):
FROM nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
(and put a simple index.html). You can deploy this variant with the same manifests (port 80 instead of 8080). This satisfies the “use any webserver as base” requirement.

# Create an EKS cluster (quick path)

eksctl create cluster \
  --name $CLUSTER --region $AWS_REGION --version 1.29 \
  --nodegroup-name ng-1 --node-type t3.medium --nodes 2 --managed

# OIDC for service accounts (enables IRSA if you add it later)
eksctl utils associate-iam-oidc-provider --region $AWS_REGION --cluster $CLUSTER --approve

# Kube context
aws eks update-kubeconfig --name $CLUSTER --region $AWS_REGION
kubectl get nodes

# Install Metrics Server (needed for HPA):
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm upgrade --install metrics-server metrics-server/metrics-server -n kube-system

# Build, push to ECR, deploy (manual first run)

# 5.1 login to ECR
aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ECR_URI

# 5.2 build & push
TAG=v1
docker build -t $APP_NAME:$TAG .
docker tag $APP_NAME:$TAG $ECR_URI:$TAG
docker push $ECR_URI:$TAG

# 5.3 deploy
sed -e "s|REPLACE_ME_ECR_URI|$ECR_URI|g" \
    -e "s|REPLACE_ME_TAG|$TAG|g" k8s/deployment.yaml > k8s/deployment.rendered.yaml

kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/deployment.rendered.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/hpa.yaml

# Wait for the ELB:
kubectl get svc -n $NS hello-svc -w
# Note EXTERNAL-IP when it appears

# Scale horizontally to 4+ pods (and verify LB)

kubectl scale deploy/hello -n $NS --replicas=4
kubectl get pods -n $NS -o wide

# Option B — let HPA do it under load
# using your external URL
export URL=http://$(kubectl get svc hello-svc -n $NS -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
# Quick loop to show load balancing + pod names
for i in {1..20}; do curl -s $URL/; echo; done
# You should see different pod hostnames in responses (round-robin via the Service). Watch HPA react (may take 1–2 mins):

kubectl get hpa -n $NS -w

# CI/CD pipeline (GitHub Actions → ECR → EKS)

IAM OIDC role for Actions

In AWS create an IAM role for GitHub OIDC with permissions to ECR (push) + EKS (describe + patch deploy). Trust policy for your repo.

Note the role ARN; set GitHub secrets:

AWS_ROLE_ARN = your role arn

AWS_REGION = your region

ECR_URI = $ECR_URI

EKS_CLUSTER = $CLUSTER

K8S_NAMESPACE = demo

# .github/workflows/ci-cd.yaml
name: build-push-deploy
on:
  push:
    branches: [ "main" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS creds
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ secrets.AWS_REGION }}

      - name: Log in to ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Set tags
        id: vars
        run: |
          echo "tag=${GITHUB_SHA::7}" >> $GITHUB_OUTPUT

      - name: Build & push image
        env:
          ECR_URI: ${{ secrets.ECR_URI }}
          TAG: ${{ steps.vars.outputs.tag }}
        run: |
          docker build -t $ECR_URI:$TAG .
          docker push $ECR_URI:$TAG
          echo "IMAGE=$ECR_URI:$TAG" >> $GITHUB_ENV

      - name: Update kubeconfig
        run: aws eks update-kubeconfig --name ${{ secrets.EKS_CLUSTER }} --region ${{ secrets.AWS_REGION }}

      - name: Render & apply manifests
        env:
          IMAGE: ${{ env.IMAGE }}
          NS: ${{ secrets.K8S_NAMESPACE }}
        run: |
          kubectl apply -f k8s/namespace.yaml
          sed -e "s|REPLACE_ME_ECR_URI:REPLACE_ME_TAG|$IMAGE|g" k8s/deployment.yaml > k8s/deployment.rendered.yaml
          kubectl apply -f k8s/deployment.rendered.yaml
          kubectl apply -f k8s/service.yaml
          kubectl apply -f k8s/hpa.yaml
          kubectl rollout status deploy/hello -n $NS --timeout=180s
# That pipeline: builds your JAR → builds/pushes a multistage image to ECR → applies manifests → waits for rollout. Each push to main deploys a new tag.

# Liveness & Readiness probes (what/why)

Readiness gate traffic until the pod is “ready”; here we probe GET /healthz every 5s.

Liveness restarts the container if the probe fails; here 15s initial delay and check every 10s.

We exposed /healthz in the app so probes return 200 immediately (no DB dependency).

# Verify end-to-end

kubectl get deploy,po,svc,hpa -n $NS → all green.

Open the EXTERNAL-IP/hostname in a browser → you’ll see “Hello from pod: …”.

Run the curl loop from step 6 to prove load balancing across 4 pods.

(Optional) kubectl top pods -n $NS to see CPU; then drive load to watch HPA scale.

# Presenting the Use Case (quick outline)

Problem statement: Build & deploy a Java web service to Kubernetes with scalable, resilient design.

Pipeline: GitHub Actions → ECR → EKS (image tags, rollout, rollback).

Containerization: Multistage Docker build (smaller, secure). Contrast with single-stage.

Kubernetes objects: Namespace, Deployment (probes, resources), Service (ELB), HPA (metrics-server).

Demo: Scale to 4 pods, curl loop shows LB across pods; drive load to auto-scale.

Next steps: Ingress/ALB Controller, TLS, external secrets, Prometheus/Grafana dashboards, Blue/Green or Canary with Argo Rollouts.

# Clean-up 
kubectl delete ns $NS
eksctl delete cluster --name $CLUSTER --region $AWS_REGION
aws ecr delete-repository --repository-name $ECR_NAME --force --region $AWS_REGION

