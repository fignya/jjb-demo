pipeline {
    agent {
        label'k8s'
    }
    options {
        timestamps()
    }
    stages {
        stage("Deploy") {
            steps{
                dir("ansible"){
                    git branch: "master", url: "git@github.com:fignya/ansible-demo.git", credentialsId: 'bh05386'
                }
                script{
                    sh "echo ${deploy_host} > hosts"
                    searchResult = httpRequest "${NEXUS_URL}service/rest/v1/search/assets?repository=maven-snapshots&maven.artifactId=${app_name}&maven.baseVersion=${version}&maven.extension=war"
                    searchResultJson = readJSON text: searchResult.content
                    env.app_url = searchResultJson['items'][0]['downloadUrl']
                    echo "url:  ${app_url}"
                    echo "name:  ${app_name}"
                    ansiblePlaybook  credentialsId: 'bh05386', disableHostKeyChecking: true, inventory: 'hosts', playbook: 'ansible/site.yml', extras: "-v"
                }
            }
        }
    }
}