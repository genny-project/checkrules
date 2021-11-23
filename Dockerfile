##FROM java:8 
#FROM openjdk:8u212-jre-alpine3.9
#RUN mkdir /rules
#COPY ./app.sh / 
#COPY ./imports.txt /
#ADD target/checkrules-fat.jar app.jar   
#RUN sh -c 'touch /app.jar'
#RUN chmod a+x /app.jar
#RUN chmod a+x /app.sh
#ENV JAVA_OPTS=""
#ENTRYPOINT ["/app.sh"]
#CMD []
#FROM java:8-jdk-alpine
FROM adoptopenjdk/openjdk11:alpine
#FROM arm64v8/openjdk:11-jre
COPY ./target/checkrules-fat.jar /usr/app/
COPY ./imports.txt /usr/app/
WORKDIR /usr/app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "checkrules-fat.jar"]
