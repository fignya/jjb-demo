pipeline {
    agent {
        label 'k8s'
    }
    options {
        timestamps()
    }
    stages {
        stage("Checkout") {
            steps {
                cleanWs()
                checkout(
                    changelog: true,
                    poll: true,
                    scm: [
                        $class                           : 'GitSCM',
                        branches                         : [[name: "${GERRIT_BRANCH}"]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions                       : [
                                [$class: 'RelativeTargetDirectory', relativeTargetDir: 'module']],
                        userRemoteConfigs                : [[
                                                                    credentialsId: 'jenkinsGerrit',
                                                                    url          : 'ssh://jenkins@j.i3c.ru:29418/All-Projects/spring-framework-petclinic.git'
                                                            ]]
                    ]
                )
                dir("ansible") {
                    git branch: "master", url: "git@github.com:fignya/ansible-tomcat.git", credentialsId: 'bh05386'
                }
            }
        }
        stage("Version set") {
            steps {
                withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4', options: [artifactsPublisher(disabled: true)]) {
                    dir('module') {
                        sh "mvn build-helper:parse-version versions:set -DnewVersion=\\\${parsedVersion.majorVersion}.\\\${parsedVersion.minorVersion}.${BUILD_NUMBER}-SNAPSHOT -B -e"
                        script {
                            pom = readMavenPom file: 'pom.xml'
                            env.version = pom.version
                            env.mysql_dbname = "pet_build_${BUILD_NUMBER}"
                        }
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
        stage("Build app") {
            steps {
                withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4', options: [artifactsPublisher(disabled: true)]) {
                    dir('module') {
                        sh "mvn clean install sonar:sonar ${MVN_SONAR_GOAL} -P MySQL -Djdbc.url='jdbc:mysql://${mysql_host}:3306/${mysql_dbname}?useUnicode=true' -Djdbc.username=${mysql_username} -Djdbc.password=${mysql_password} -B -e"
                    }
                }
            }
        }
        stage("Deploy to Nexus") {
            steps {
                withMaven(mavenLocalRepo: '.repository', jdk: 'JDK8', maven: 'MAVEN_3.5.4', options: [junitPublisher(disabled: true), jacocoPublisher(disabled: true)]) {
                    dir('module') {
                        sh "mvn deploy -P MySQL -DskipTests=true -DaltDeploymentRepository=maven-snapshots::default::${NEXUS_URL}repository/maven-snapshots/ -B -e -X"
                        sshagent(['jenkinsGerrit']) {
                            sh "git tag -f ${version}"
                            sh "git push -f --tags"
                        }
                    }
                }
            }
        }
        stage("Deployment") {
            steps {
                echo "Version: ${version}"
                build job: 'petclinic/Deployment', parameters: [string(name: 'version', value: "${version}")]

            }
        }
        stage("Promotion") {
            steps {
                echo "Version: ${version}"
                build job: 'petclinic/Promotion', wait: false, parameters: [string(name: 'version', value: "${version}")]
            }
        }
    }
}