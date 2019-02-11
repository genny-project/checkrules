FROM java:8 
#FROM openjdk:8-jre-slim
#FROM openjdk:8-jre-alpine
RUN mkdir /rules
COPY ./app.sh / 
COPY ./imports.txt /
ADD target/checkrules-fat.jar app.jar   
RUN sh -c 'touch /app.jar'
ENV JAVA_OPTS=""
ENTRYPOINT ["/app.sh"]
CMD []
