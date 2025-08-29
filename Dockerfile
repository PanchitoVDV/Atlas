# Dockerfile

# Use a lightweight Java 21 base image
FROM eclipse-temurin:21-jre-alpine

# Set the working directory inside the container
WORKDIR /root/atlas

# Copy your application JAR from the host's 'Atlas' folder into the container
# It finds "atlas-base-1.0.0.jar" and renames it to "atlas.jar" inside the image
COPY atlas-base-1.0.0.jar atlas.jar

# Expose the application's ports
EXPOSE 8080 9090

# The command to run when the container starts
CMD ["java", "-jar", "atlas.jar"]