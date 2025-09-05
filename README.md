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
