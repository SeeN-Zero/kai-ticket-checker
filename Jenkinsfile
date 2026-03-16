pipeline {
    agent any

    environment {
        IMAGE_NAME = "kai-ticket-checker"
        IMAGE_TAG  = "latest"
        BASE_IMAGE = "kai-ticket-checker-base:latest"
        DOCKER_DIR = "docker"

        KAI_TELEGRAM_BOT_TOKEN    = credentials('KAI_TELEGRAM_BOT_TOKEN')
        KAI_SUBSCRIPTION_PASSWORD = credentials('KAI_SUBSCRIPTION_PASSWORD')
        KAI_DB_USERNAME           = credentials('KAI_DB_USERNAME')
        KAI_DB_PASSWORD           = credentials('KAI_DB_PASSWORD')
        KAI_DB_JDBC_URL           = credentials('KAI_DB_JDBC_URL')

        KAI_SCHEDULER_EVERY   = "${env.KAI_SCHEDULER_EVERY ?: '10m'}"
        KAI_SCHEDULER_ENABLED = "${env.KAI_SCHEDULER_ENABLED ?: 'true'}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Ensure Base Image') {
            steps {
                script {
                    def baseImageExists = sh(script: "docker images -q ${BASE_IMAGE}", returnStdout: true).trim()
                    
                    if (!baseImageExists) {
                        echo "Base image ${BASE_IMAGE} tidak ditemukan. Memulai build base image..."
                        // Kita build dari root context karena biasanya lebih aman jika ada file yang dibutuhkan di luar folder base
                        // Namun di sini base/Dockerfile cukup mandiri.
                        sh "docker build -t ${BASE_IMAGE} -f ${DOCKER_DIR}/base/Dockerfile ."
                    } else {
                        echo "Base image ${BASE_IMAGE} sudah tersedia."
                    }
                }
            }
        }

        stage('Build Artifact') {
            steps {
                sh 'sed -i "s/\\r$//" mvnw'
                sh 'chmod +x mvnw'
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    sh "docker build --build-arg BASE_IMAGE=${BASE_IMAGE} -t ${IMAGE_NAME}:${IMAGE_TAG} -f ${DOCKER_DIR}/Dockerfile ."
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    dir("${DOCKER_DIR}") {
                        //sh "docker compose up -d --no-build"
                        sh "docker compose -f ${DOCKER_DIR}/docker-compose.yml up -d --build"
                    }
                }
            }
        }
    }

    post {
        always {
            // Pembersihan artifact atau log jika diperlukan
            echo 'Pipeline selesai dikerjakan.'
        }
        success {
            echo 'Aplikasi berhasil di-deploy!'
        }
        failure {
            echo 'Pipeline gagal. Silakan periksa log.'
        }
    }
}
