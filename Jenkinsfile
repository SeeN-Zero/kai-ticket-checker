pipeline {
    agent any

    environment {
        IMAGE_NAME = "kai-ticket-checker"
        IMAGE_TAG  = "latest"
        BASE_IMAGE = "kai-ticket-checker-base:latest"
        DOCKER_DIR = "docker"

        SERVER_USER        = credentials('SERVER_USER')
        SERVER_IP          = credentials('SERVER_IP_G14')
        DEPLOY_PATH        = "/home/${SERVER_USER}/${IMAGE_NAME}"
        SSH_CREDENTIALS_ID = 'ssh-server-credentials' // Gunakan ID string di sini jika menggunakan sshagent

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

        stage('Build Package') {
            steps {
                sh 'sed -i "s/\\r$//" mvnw'
                sh 'chmod +x mvnw'
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Deploy via SSH') {
            steps {
                sshagent(["${SSH_CREDENTIALS_ID}"]) {
                    script {
                        echo "Menyiapkan direktori di server: ${DEPLOY_PATH}"
                        sh "ssh -o StrictHostKeyChecking=no ${SERVER_USER}@${SERVER_IP} 'mkdir -p ${DEPLOY_PATH}/target ${DEPLOY_PATH}/${DOCKER_DIR}/base'"

                        echo "Mengirim file ke server..."
                        // Kirim file yang dibutuhkan untuk build docker
                        sh "scp -o StrictHostKeyChecking=no target/*-runner.jar ${SERVER_USER}@${SERVER_IP}:${DEPLOY_PATH}/target/"
                        sh "scp -o StrictHostKeyChecking=no ${DOCKER_DIR}/Dockerfile ${SERVER_USER}@${SERVER_IP}:${DEPLOY_PATH}/${DOCKER_DIR}/"
                        sh "scp -o StrictHostKeyChecking=no ${DOCKER_DIR}/docker-compose.yml ${SERVER_USER}@${SERVER_IP}:${DEPLOY_PATH}/${DOCKER_DIR}/"
                        sh "scp -o StrictHostKeyChecking=no ${DOCKER_DIR}/base/Dockerfile ${SERVER_USER}@${SERVER_IP}:${DEPLOY_PATH}/${DOCKER_DIR}/base/"
                        sh "scp -o StrictHostKeyChecking=no ${DOCKER_DIR}/deploy.sh ${SERVER_USER}@${SERVER_IP}:${DEPLOY_PATH}/${DOCKER_DIR}/"

                        echo "Menjalankan script deploy di server..."
                        sh """
                            ssh -o StrictHostKeyChecking=no ${SERVER_USER}@${SERVER_IP} '
                                export KAI_TELEGRAM_BOT_TOKEN="${KAI_TELEGRAM_BOT_TOKEN}"
                                export KAI_SUBSCRIPTION_PASSWORD="${KAI_SUBSCRIPTION_PASSWORD}"
                                export KAI_DB_USERNAME="${KAI_DB_USERNAME}"
                                export KAI_DB_PASSWORD="${KAI_DB_PASSWORD}"
                                export KAI_DB_JDBC_URL="${KAI_DB_JDBC_URL}"
                                export KAI_SCHEDULER_EVERY="${KAI_SCHEDULER_EVERY}"
                                export KAI_SCHEDULER_ENABLED="${KAI_SCHEDULER_ENABLED}"
                                
                                chmod +x ${DEPLOY_PATH}/${DOCKER_DIR}/deploy.sh
                                bash ${DEPLOY_PATH}/${DOCKER_DIR}/deploy.sh
                            '
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
