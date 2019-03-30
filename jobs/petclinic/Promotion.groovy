pipeline {
    agent {
        label'k8s'
    }
    options {
        timestamps()
    }
    stages {
        stage("Promotion") {
            steps {
                timeout(10) {
                    input message: 'Let\'s promote?', ok: 'Promote'
                }
            }
        }
        stage("Checkout") {
            steps{
                updateGitlabCommitStatus name: 'Jenkins', state: 'running'
                cleanWs()
                dir("module"){
                    git branch: "dev", url: "ssh://jenkins@j.i3c.ru:29418/All-Projects/spring-framework-petclinic.git", credentialsId: 'jenkinsGerrit'
                }
                dir("ansible") {
                    git branch: "master", url: "git@github.com:fignya/ansible-tomcat.git", credentialsId: 'bh05386'
                }
            }
        }
        stage("Version set") {
            steps {
                dir('module') {
                    withMaven( mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4' , options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn build-helper:parse-version versions:set -DnewVersion=\\\${parsedVersion.majorVersion}.\\\${parsedVersion.minorVersion}.${BUILD_NUMBER} -B -e"
                    }
                    script {
                        pom = readMavenPom file: 'pom.xml'
                        env.release_version = pom.version
                        env.mysql_dbname = "pet_build_${BUILD_NUMBER}"
                        echo "New version: ${release_version}"
                    }
                    sshagent(['jenkinsGerrit']) {
                        sh """
                            git add pom.xml
                            git commit -m \"petclinic version updated to ${release_version}\"
                            """
                    }
                }
            }
        }
        stage("Prepare db") {
            steps {
                script {

                    sh "echo ${deploy_host} > hosts"
                    ansiblePlaybook credentialsId: 'bh05386', disableHostKeyChecking: true, inventory: 'hosts', playbook: 'ansible/site.yml', tags: 'db'
                }
            }
        }
        stage("Build release app") {
            steps {
                dir('module') {
                    withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4', options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn clean install -P MySQL -Djdbc.url='jdbc:mysql://${mysql_host}:3306/${mysql_dbname}?useUnicode=true' -Djdbc.username=${mysql_username} -Djdbc.password=${mysql_password} -B -e"
                    }
                }
            }
        }
        stage("Deploy to Nexus") {
            steps {
                dir('module') {
                    withMaven( mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4', options: [junitPublisher(disabled: true), jacocoPublisher(disabled: true)]) {
                        sh "mvn deploy -P MySQL -DskipTests=true -DaltDeploymentRepository=maven-releases::default::${NEXUS_URL}repository/maven-releases/ -B -e"
                    }
                    sshagent(['jenkinsGerrit']) {
                        sh """
                        git push origin dev
                        git tag -f ${release_version}
                        git push --tags
                        """
                    }
                }
            }
        }
    }
}