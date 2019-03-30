pipeline {
    agent {
        label 'k8s'
    }
    options {
        timestamps()
    }
    environment {
        GERRIT_CREDENTIALS_ID = "userGerrit"
    }
    stages {
        stage("Checkout") {
            steps {
                gerritReview labels: [Verified: 0]
                cleanWs()
                checkout(
                        changelog: true,
                        poll: true,
                        scm: [
                                $class                           : 'GitSCM',
                                branches                         : [[name: "$GERRIT_REFSPEC"]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions                       : [
                                        [$class: 'RelativeTargetDirectory', relativeTargetDir: 'module']],
                                userRemoteConfigs                : [[
                                                                            credentialsId: 'jenkinsGerrit',
                                                                            refspec      : 'refs/changes/*:refs/changes/*',
                                                                            url          : 'ssh://jenkins@j.i3c.ru:29418/All-Projects/spring-framework-petclinic.git'
                                                                    ]]
                        ]
                )
                dir("ansible") {
                    git branch: "master", url: "git@github.com:fignya/ansible-tomcat.git", credentialsId: 'bh05386'
                }
            }
        }
        stage("version set") {
            steps {
                withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4') {
                    dir('module') {
                        sh "env | sort"
                        sh "mvn build-helper:parse-version versions:set -DnewVersion=\\\${parsedVersion.majorVersion}.\\\${parsedVersion.minorVersion}.${BUILD_NUMBER}-SNAPSHOT -B -e"
                        script {
                            pom = readMavenPom file: 'pom.xml'
                            env.version = pom.version
                            env.mysql_dbname = "pet_review_${BUILD_NUMBER}"
                        }
                    }
                }
            }
        }
        stage("Prepare BD") {
            steps {
                script {

                    sh "echo ${deploy_host} > hosts"
                    ansiblePlaybook credentialsId: 'bh05386', disableHostKeyChecking: true, inventory: 'hosts', playbook: 'ansible/site.yml', tags: 'db'
                }
            }
        }
        stage("build app") {
            steps {
                withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4') {
                    dir('module') {
                        sh "mvn clean install -P MySQL -B -e -Djdbc.url='jdbc:mysql://${mysql_host}:3306/${mysql_dbname}?useUnicode=true' -Djdbc.username=${mysql_username} -Djdbc.password=${mysql_password}"
                    }
                }
            }
        }
        stage("sonar analyze") {
            steps {
                withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4') {
                    dir('module') {
                        sh "mvn ${MVN_SONAR_GOAL} -Dsonar.analysis.mode=preview -B -e"
                    }
                }
                sonarToGerrit(
                        inspectionConfig: [
                                serverURL : "${SONAR_URL}",
                                baseConfig: [
                                        projectPath    : '',
                                        sonarReportPath: 'module/target/sonar/sonar-report.json',
                                        autoMatch      : true
                                ]
                        ]
                )
            }
        }
    }
    post {
        always { logstashSend failBuild: false, maxLines: -1 }
        success { gerritReview labels: [Verified: 1] }
        unstable { gerritReview labels: [Verified: 0], message: 'Build is unstable' }
        failure { gerritReview labels: [Verified: -1] }
    }
}