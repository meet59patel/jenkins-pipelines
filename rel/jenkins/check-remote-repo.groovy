def awsCredentials = [[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: '4422eb0c-26be-4454-8823-fc76b9b3b120']]

void sendMessage(String REPO_NAME, String REPO_URL) {
    TAGS = sh (
        script: "cat ${REPO_NAME}.newtags",
        returnStdout: true
    )
 
    if (TAGS) {
        slackSend channel: "${ReposSlackMap["$REPO_NAME"]}", color: '#00FF00', message: "[${JOB_NAME}]: New tag(s) have been created for ${REPO_NAME}, please check the following link(s):\n${TAGS}"
    } else {
        sh (script: "rm -rf ${REPO_NAME}.newtags", returnStdout: false)
    }
}
void checkRepo(String REPO_NAME, String REPO_URL) {
    popArtifactFile("${REPO_NAME}.last")
    sh """
        if [ -n ${REPO_NAME}.last ]; then
            git ls-remote --tags --refs ${REPO_URL} | awk -F'/' '{print \$3}' | sort | uniq > ${REPO_NAME}.current
            cat ${REPO_NAME}.last ${REPO_NAME}.current | sort | uniq -u | awk '{print "$REPO_URL/tree/"\$1}' > ${REPO_NAME}.newtags
            mv -fv ${REPO_NAME}.current ${REPO_NAME}.last
        else
            git ls-remote --tags --refs ${REPO_URL} | awk -F'/' '{print \$3}' | sort | uniq > ${REPO_NAME}.last
        fi
        
    """
    pushArtifactFile("${REPO_NAME}.last")
}

void pushArtifactFile(String FILE_NAME) {
    sh """
        S3_PATH=s3://rel-repo-cache
        aws s3 ls \$S3_PATH/${FILE_NAME} || :
        aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
    """
}

void popArtifactFile(String FILE_NAME) {
    sh """
        S3_PATH=s3://rel-repo-cache
        aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
    """
}

def GitHubURL='https://github.com'
ReposPathMap = [
    'MongoDB':           "${GitHubURL}/mongodb/mongo",
    'MySQL':             "${GitHubURL}/mysql/mysql-server",
    'MySQLShell':        "${GitHubURL}/mysql/mysql-shell",
    'ProxySQL':          "${GitHubURL}/sysown/proxysql",
    'MyRocks':           "${GitHubURL}/facebook/mysql-5.6",
    'RocksDB':           "${GitHubURL}/facebook/rocksdb",
    'Grafana':           "${GitHubURL}/grafana/grafana",
    'Consul':            "${GitHubURL}/hashicorp/consul",
    'Prometheus':        "${GitHubURL}/prometheus/prometheus",
    'Orchestrator':      "${GitHubURL}/outbrain/orchestrator",
    'SysBench':          "${GitHubURL}/akopytov/sysbench",
    'Patroni':           "${GitHubURL}/zalando/patroni",
    'PgAudit':           "${GitHubURL}/pgaudit/pgaudit",
    'PgBackrest':        "${GitHubURL}/pgbackrest/pgbackrest",
    'PgRepack':          "${GitHubURL}/reorg/pg_repack",
    'PostgreSQL-Common': "https://salsa.debian.org/postgresql/postgresql-common",
    'PostgreSQL':        "git://git.postgresql.org/git/postgresql"
    ]

ReposSlackMap = [
    'MongoDB':           "#opensource-psmdb",
    'MySQL':             "#opensource-ps",
    'MySQLShell':        "#opensource-ps",
    'ProxySQL':          "#opensource_proxysql",
    'MyRocks':           "#opensource-myrocks",
    'RocksDB':           "#opensource-myrocks",
    'Grafana':           "#pmm",
    'Consul':            "#pmm",
    'Prometheus':        "#pmm",
    'Orchestrator':      "#pmm",
    'SysBench':          "#opensource",
    'Patroni':           "#postgresql-build",
    'PgAudit':           "#postgresql-build",
    'PgBackrest':        "#postgresql-build",
    'PgRepack':          "#postgresql-build",
    'PostgreSQL-Common': "#postgresql-build",
    'PostgreSQL':        "#postgresql-build"
    ]

pipeline {
    agent {
        label 'micro-amazon'
    }
    triggers {
        cron('H 8 * * *')
    }
    options {
        skipStagesAfterUnstable()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        withCredentials(awsCredentials)
    }
    stages {
        stage('Check Repos') {
            steps {
                script {
                    ReposPathMap.each { repo_info ->
                        checkRepo("${repo_info.key}", "${repo_info.value}")
                    }
                }
                stash allowEmpty: true, includes: "*.newtag", name: "NewTagsFiles"
            }
        }
        stage('Sending notifications') {
            steps {
                unstash "NewTagsFiles"
                script {
                    ReposPathMap.each { repo_info ->
                        sendMessage("${repo_info.key}", "${repo_info.value}")
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.newtags', allowEmptyArchive: true
            deleteDir()
        }
    }
}

