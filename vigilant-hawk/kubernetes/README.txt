minikube start --memory 4096
minikube addons enable ingress

eval $(minikube -p minikube docker-env)

sbt docker:publishLocal


kubectl apply -f kubernetes/namespace.json

kubectl config set-context --current --namespace=appka-1

kubectl apply -f kubernetes/akka-cluster.yml

kubectl expose deployment appka --type=LoadBalancer --name=appka-service

kubectl cluster-info
minikube dashboard

minikube delete



kubectl create configmap prometheus-config --from-file=kubernetes/prometheus.yml
kubectl create -f kubernetes/prometheus-deploy.yml
#kubectl expose deployment prometheus-deployment --type=NodePort --name=prometheus-service

minikube service prometheus-service --url
minikube service appka-service --url -n appka-1

kubectl create -f kubernetes/grafana-datasource-config.yaml
kubectl create -f kubernetes/grafana-deployment.yaml

minikube service grafana --url