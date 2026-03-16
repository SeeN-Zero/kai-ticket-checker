pipeline {
    agent any

    environment {
        // Konfigurasi Image
        IMAGE_NAME = "kai-ticket-checker"
        IMAGE_TAG  = "latest"
        BASE_IMAGE = "kai-ticket-checker-base:latest"
        DOCKER_DIR = "docker"

        // Variabel Sensitif (Harus di-set di Jenkins Credentials dengan ID yang sama)
        KAI_TELEGRAM_BOT_TOKEN    = credentials('KAI_TELEGRAM_BOT_TOKEN')
        KAI_SUBSCRIPTION_PASSWORD = credentials('KAI_SUBSCRIPTION_PASSWORD')
        KAI_DB_USERNAME           = credentials('KAI_DB_USERNAME')
        KAI_DB_PASSWORD           = credentials('KAI_DB_PASSWORD')
        KAI_DB_JDBC_URL           = credentials('KAI_DB_JDBC_URL')

        // Variabel Konfigurasi (Menggunakan default dari permintaan Anda)
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
                    // Cek apakah base image sudah ada di lokal node
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
                // Menggunakan Maven Wrapper yang ada di project
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    // Build image menggunakan Dockerfile yang ada
                    // Kita bisa menggunakan docker-compose untuk build atau perintah docker build langsung
                    sh "docker build --build-arg BASE_IMAGE=${BASE_IMAGE} -t ${IMAGE_NAME}:${IMAGE_TAG} -f ${DOCKER_DIR}/Dockerfile ."
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    // Deployment menggunakan docker-compose
                    // Gunakan --env-file jika ingin menggunakan file tertentu, 
                    // namun di sini kita mengandalkan environment variable yang di-inject Jenkins
                    dir("${DOCKER_DIR}") {
                        sh "docker-compose up -d --no-build"
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
