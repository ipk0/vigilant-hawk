#!/bin/bash

set -exu

minikube delete

minikube start

eval $(minikube -p minikube docker-env)

sbt docker:publishLocal

kubectl create configmap prometheus-config --from-file=kubernetes/prometheus.yml
kubectl create -f kubernetes/prometheus-deploy.yml

kubectl create -f kubernetes/grafana-datasource-config.yaml
kubectl create -f kubernetes/grafana-deployment.yaml

kubectl apply -f kubernetes/namespace.json
kubectl config set-context --current --namespace=appka-1
kubectl apply -f kubernetes/akka-cluster.yml #--namespace=appka-1
kubectl expose deployment appka --type=LoadBalancer --name=appka-service
