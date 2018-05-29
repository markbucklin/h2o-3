def call(final pipelineContext, final Closure body) {
  final List<String> FILES_TO_EXCLUDE = [
    '**/rest.log', '**/*prediction*.csv', '**/java*_*.out.txt'
  ]

  final List<String> FILES_TO_ARCHIVE = [
    '**/*.log', '**/out.*',
    '**/results/*.txt', '**/results/failed/*.txt',
    '**/results/*.code', '**/results/failed/*.code',
  ]

  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  if (config.hasJUnit == null) {
    config.hasJUnit = true
  }

  if (config.activatePythonEnv == null) {
    config.activatePythonEnv = true
  }
  if (config.activateR == null) {
    config.activateR = true
  }

  config.h2o3dir = config.h2o3dir ?: 'h2o-3'

  if (config.customBuildAction == null) {
    def makeVars = []
    def additionalGradleOpts = pipelineContext.getBuildConfig().getAdditionalGradleOpts()
    if (additionalGradleOpts != null && !additionalGradleOpts.isEmpty()) {
      makeVars += "ADDITIONAL_GRADLE_OPTS='${pipelineContext.getBuildConfig().getAdditionalGradleOpts().join(' ')}'"
    }

    config.customBuildAction = """
      echo "Activating Java ${env.JAVA_VERSION}"
      . /usr/bin/activate_java_${env.JAVA_VERSION}
      java -version 
      javac -version

      if [ "${config.activatePythonEnv}" = 'true' ]; then
        echo "Activating Python ${env.PYTHON_VERSION}"
        . /envs/h2o_env_python${env.PYTHON_VERSION}/bin/activate
      fi

      if [ "${config.activateR}" = 'true' ]; then
        echo "Activating R ${env.R_VERSION}"
        activate_R_${env.R_VERSION}
      fi

      echo "Running Make"
      export ${makeVars.join(' ')}
      make -f ${config.makefilePath} ${config.target}
    """
  }

  boolean success = false
  try {
    execMake(config.customBuildAction, config.h2o3dir)
    success = true
  } finally {
    if (config.hasJUnit) {
      final GString findCmd = "find ${config.h2o3dir} -type f -name '*.xml'"
      final GString replaceCmd = "${findCmd} -exec sed -i 's/&#[0-9]\\+;//g' {} +"
      sh replaceCmd
      pipelineContext.getUtils().archiveJUnitResults(this, config.h2o3dir)
    }
    if (config.archiveFiles && !success) {
      pipelineContext.getUtils().archiveStageFiles(this, config.h2o3dir, FILES_TO_ARCHIVE, FILES_TO_EXCLUDE)
    }
    if (config.archiveAdditionalFiles) {
      echo "###### Archiving additional files: ######"
      echo "${config.archiveAdditionalFiles.join(', ')}"
      pipelineContext.getUtils().archiveStageFiles(this, config.h2o3dir, config.archiveAdditionalFiles, config.excludeAdditionalFiles)
    }
  }
}

private void execMake(final String buildAction, final String h2o3dir) {
  sh """
    export JAVA_HOME=/usr/lib/jvm/java-current-oracle
    export PATH=\${JAVA_HOME}/bin:\${PATH}

    cd ${h2o3dir}
    echo "Linking small and bigdata"
    rm -f smalldata
    ln -s -f /home/0xdiag/smalldata
    rm -f bigdata
    ln -s -f /home/0xdiag/bigdata

    # The Gradle fails if there is a special character, in these variables
    unset CHANGE_AUTHOR_DISPLAY_NAME
    unset CHANGE_TITLE

    printenv
    ${buildAction}
  """
}

return this
