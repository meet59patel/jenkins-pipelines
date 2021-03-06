void pushArtifactFile(String FILE_NAME, String GIT_SHORT_COMMIT) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${GIT_SHORT_COMMIT}
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME, String GIT_SHORT_COMMIT) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${GIT_SHORT_COMMIT}
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}

TestsReport = '<testsuite  name=\\"PXC\\">\n'
testsReportMap = [:]
void makeReport() {
    for ( test in testsReportMap ) {
        TestsReport = TestsReport + "<testcase name=\\\"${test.key}\\\"><${test.value}/></testcase>\n"
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void runTest(String TEST_NAME) {
    try {
        echo "The $TEST_NAME test was started!"

        GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
        VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
        FILE_NAME = "$VERSION-$TEST_NAME-minikube-${env.KUBER_VERSION}"
        testsReportMap[TEST_NAME] = 'failure'

        popArtifactFile("$FILE_NAME", "$GIT_SHORT_COMMIT")
        sh """
            if [ -f "$FILE_NAME" ]; then
                echo Skip $TEST_NAME test
            else
                cd ./source
                if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                    export IMAGE=${PXC_OPERATOR_IMAGE}
                else
                    export IMAGE=perconalab/percona-xtradb-cluster-operator:${env.GIT_BRANCH}
                fi

                if [ -n "${IMAGE_PXC}" ]; then
                    export IMAGE_PXC=${IMAGE_PXC}
                fi

                if [ -n "${IMAGE_PROXY}" ]; then
                    export IMAGE_PROXY=${IMAGE_PROXY}
                fi

                if [ -n "${IMAGE_BACKUP}" ]; then
                    export IMAGE_BACKUP=${IMAGE_BACKUP}
                fi

                if [ -n "${IMAGE_PMM}" ]; then
                    export IMAGE_PMM=${IMAGE_PMM}
                fi

                ./e2e-tests/$TEST_NAME/run
            fi
        """
        pushArtifactFile("$FILE_NAME", "$GIT_SHORT_COMMIT")
        testsReportMap[TEST_NAME] = 'passed'
    }
    catch (exc) {
        currentBuild.result = 'FAILURE'
    }

    sh """
        rm -rf $FILE_NAME
    """

    echo "The $TEST_NAME test was finished!"
}
void installRpms() {
    sh '''
        cat <<EOF > /tmp/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://packages.cloud.google.com/yum/repos/kubernetes-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/yum-key.gpg https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo mv /tmp/kubernetes.repo /etc/yum.repos.d/

        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq kubectl socat || true
    '''
}
pipeline {
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:master',
            name: 'PXC_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:master-pxc5.7',
            name: 'IMAGE_PXC')
        string(
            defaultValue: '',
            description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:master-proxysql',
            name: 'IMAGE_PROXY')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:master-pxc5.7-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-server-mongodb-operator:master-pmm',
            name: 'IMAGE_PMM')
        string(
            defaultValue: 'v1.14.8',
            description: 'Kubernetes Version',
            name: 'KUBER_VERSION',
            trim: true)
    }
    agent {
         label 'micro-amazon' 
    }
    options {
        skipDefaultCheckout()
    }

    stages {
        stage('Prepare') {
            agent { label 'docker' }
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES", useDefaultExcludes: false
            }
        }
        
        stage('Build docker image') {
            agent { label 'docker' }
            steps {
                sh '''
                    sudo rm -rf source
                '''
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PXC operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }
        stage('Tests') {
            agent { label 'docker-32gb' }
                steps {
                    sh '''
                        if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                            rm -rf $HOME/google-cloud-sdk
                            curl https://sdk.cloud.google.com | bash
                        fi

                        source $HOME/google-cloud-sdk/path.bash.inc
                        gcloud components install alpha
                        gcloud components install kubectl

                        curl -s https://storage.googleapis.com/kubernetes-helm/helm-v2.12.1-linux-amd64.tar.gz \
                            | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                        sudo curl -Lo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
                        sudo chmod +x /usr/local/bin/minikube
                        export CHANGE_MINIKUBE_NONE_USER=true
                        sudo -E /usr/local/bin/minikube start --vm-driver=none --kubernetes-version ${KUBER_VERSION}
                        sudo mv /root/.kube /root/.minikube $HOME
                        sudo chown -R $USER $HOME/.kube $HOME/.minikube
                        sed -i s:/root:$HOME:g $HOME/.kube/config
                    '''
                    
                    unstash "sourceFILES"
                    withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                        sh '''
                           cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        '''
                    }

                    installRpms()
                    runTest('limits')
                    runTest('scaling')
                    runTest('affinity')
                    runTest('one-pod')
                    runTest('upgrade-consistency')
                    runTest('self-healing-advanced')
                    runTest('operator-self-healing')
            }
            post {
                always {
                    sh '''
                        /usr/local/bin/minikube delete || true
                        sudo rm -rf $HOME/google-cloud-sdk
                        sudo rm -rf ./*
                    '''
                    deleteDir()
                }
            }
        }
        stage('Make report') {
            steps {
                makeReport()
                sh """
                    echo "${TestsReport}" > TestsReport.xml
                """
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts '*.xml'
            }
        }
    }

    post {
        always {
            sh '''
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
