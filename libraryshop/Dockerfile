FROM nginx:alpine
COPY index.html styles.css app.js /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/templates/default.conf.template
ENV BACKEND_URL=http://backend:8080
ENV NGINX_ENVSUBST_FILTER=^BACKEND_URL$
EXPOSE 80
