#!/bin/bash

docker run \
   -p 9090:9090 \
   -v /Users/ivan.puchko/projects/phd/vigilant-hawk/vigilant-hawk/docker/prometheus.yml:/etc/prometheus/prometheus.yml \
   prom/prometheus
