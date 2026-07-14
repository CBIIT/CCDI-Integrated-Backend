# Build stage
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests

# Runtime stage - Tomcat 11 with JRE 21
FROM tomcat:11.0-jre21-temurin AS final

ENV CATALINA_HOME=/usr/local/tomcat
ENV PATH=$CATALINA_HOME/bin:$PATH
ENV TOMCAT_VERSION=11.0.23

# Apply latest distro security patches at build time.
RUN apt-get update && \
    apt-get -y upgrade && \
    apt-get -y purge wget curl locales && \
    apt-get -y autoremove && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN rm -rf ${CATALINA_HOME}/webapps.dist \
           ${CATALINA_HOME}/webapps/ROOT \
           ${CATALINA_HOME}/webapps/docs \
           ${CATALINA_HOME}/webapps/examples \
           ${CATALINA_HOME}/webapps/host-manager \
           ${CATALINA_HOME}/webapps/manager

# Security hardening - hide server info in error pages
RUN sed -i 's|</Host>|  <Valve className="org.apache.catalina.valves.ErrorReportValve"\n               showReport="false"\n               showServerInfo="false" />\n\n      </Host>|' ${CATALINA_HOME}/conf/server.xml

WORKDIR ${CATALINA_HOME}

EXPOSE 8080

COPY --from=build /usr/src/app/target/Bento-0.0.1.war ${CATALINA_HOME}/webapps/ROOT.war

CMD ["catalina.sh", "run"]