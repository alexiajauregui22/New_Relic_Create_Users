pipeline {
    agent any
    options { durabilityHint('PERFORMANCE_OPTIMIZED') }
    parameters {
        base64File(name: 'INPUT_FILE', description: 'CSV: Nombres;Apellidos;Correo;Ticket;GrupoID')
        booleanParam(name: 'DRY_RUN', defaultValue: true, description: 'Preview')
        string(name: 'NEW_RELIC_SP_ID',   defaultValue: '0527478c-90a6-4e57-b67c-dd9291a979bc', description: 'Service Principal ID de New Relic')
        string(name: 'NEW_RELIC_ROLE_ID', defaultValue: 'faa74f6b-9caf-4561-8423-733aad8054d3', description: 'App Role ID (User) de New Relic')
    }
    environment {
        AZURE_CLIENT_ID     = credentials('AZURE_CLIENT_ID')
        AZURE_CLIENT_SECRET = credentials('AZURE_CLIENT_SECRET')
        AZURE_TENANT_ID     = credentials('AZURE_TENANT_ID')
        TENANT_DOMAIN       = 'marcoantonioochantetelefoni.onmicrosoft.com'
    }
    stages {
        stage('CSV') {
            steps {
                script {
                    sh "python3 -c \"import base64; open('input.csv','wb').write(base64.b64decode('${params.INPUT_FILE}'))\""
                    //def lines = sh(script: "python3 -c \"import csv; r=csv.reader(open('input.csv','r',encoding='latin-1'),delimiter=','); next(r); [print(','.join(row[:5])) for row in r if any(row)]\"", returnStdout: true).trim().split('\n')
                    def lines = sh(script: "python3 -c \"import csv; r=csv.reader(open('input.csv','r',encoding='latin-1'),delimiter=';'); next(r); [print(';'.join(row[:5])) for row in r if any(row) and any(x.strip() for x in row)]\"", returnStdout: true).trim().split('\n')
                    env.USUARIOS = lines.join('\n')
                    echo "📋 ${lines.size()} usuarios"
                }
            }
        }
        stage('Login') {
            when { expression { !params.DRY_RUN } }
            steps { sh 'az login --service-principal -u $AZURE_CLIENT_ID -p $AZURE_CLIENT_SECRET --tenant $AZURE_TENANT_ID --allow-no-subscriptions' }
        }
        stage('Usuarios ExternalAzureAD') {
            when { expression { !params.DRY_RUN } }
            steps {
                script {
                    def lines = env.USUARIOS.split('\n')
                    def stats = [invited:0, existing:0, newGroups:0, skipped:0, newRelic:0, failed:0]
                    lines.each { line ->
                        def cols = line.split(';')
                        if (cols.size() < 4) return
            
                        def nombre      = cols[0].trim()
                        def apellido    = cols[1].trim()
                        def email       = cols[2].trim()
                        def ticket      = cols[3].trim()
                        def grupoId = cols.size() >= 5 ? cols[4]?.trim() : ''
                        def displayName = "${nombre} ${apellido}".trim()

                        echo "\n👤 ${displayName} | 📧 ${email} | 🎫 ${ticket}"

                        // 1️⃣ BUSCAR SI YA EXISTE como guest (ExternalAzureAD)
                        def userId = sh(
                            script: """
                                az ad user list \\
                                    --filter "mail eq '${email}'" \\
                                    --query '[0].id' -o tsv 2>/dev/null || echo ''
                            """,
                            returnStdout: true
                        ).trim()

                        if (!userId || userId == 'None') {
                            // 2️⃣ INVITAR como B2B Guest → ExternalAzureAD
                            echo "   📨 Invitando como ExternalAzureAD..."
                            def inviteResult = sh(
                                script: """
                                    az rest --method POST \\
                                        --uri https://graph.microsoft.com/v1.0/invitations \\
                                        --body '{
                                            "invitedUserEmailAddress": "${email}",
                                            "invitedUserDisplayName": "${displayName}",
                                            "inviteRedirectUrl": "https://myapps.microsoft.com",
                                            "sendInvitationMessage": false,
                                            "invitedUserType": "Guest"
                                        }' | jq -r '.invitedUser.id // empty'
                                """,
                                returnStdout: true
                            ).trim()

                            if (inviteResult && inviteResult != 'null') {
                                userId = inviteResult
                                stats.invited++
                                echo "   ✅ Invitado (ExternalAzureAD): ${userId}"
                            } else {
                                stats.failed++
                                echo "   ❌ Error al invitar ${email}"
                                return
                            }
                        } else {
                            stats.existing++
                            echo "   🔄 Ya existe: ${userId}"
                        }

                        // 3️⃣ ACTUALIZAR PROPIEDADES (department = ticket, nombre, apellido)
                        sh """
                            az rest --method PATCH \\
                                --uri "https://graph.microsoft.com/v1.0/users/${userId}" \\
                                --body '{
                                    "department": "${ticket}",
                                    "givenName":  "${nombre}",
                                    "surname":    "${apellido}"
                                }' || true
                        """
                        echo "   ✅ Props actualizadas: ${ticket}"

                        // 4️⃣ VERIFICAR/ASIGNAR GRUPO (original intacto)
                        if (grupoId) {
                            sleep(3)
                            def yaEnGrupo = sh(
                                script: """
                                    az ad group member check \\
                                        --group "${grupoId}" --member-id "${userId}" \\
                                        | jq -r '.value // false'
                                """,
                                returnStdout: true
                            ).trim() == 'true'

                            if (yaEnGrupo) {
                                stats.skipped++
                                echo "   ⏭️  Ya en grupo"
                            } else {
                                sh "az ad group member add --group ${grupoId} --member-id ${userId}"
                                stats.newGroups++
                                echo "   👥 Agregado al grupo"
                            }
                        }

                        // 5️⃣ ASIGNAR A NEW RELIC (nuevo)
                        if (params.NEW_RELIC_SP_ID) {
                            echo "   🔷 Verificando asignación en New Relic..."

                            def yaEnNewRelic = sh(
                                script: """
                                    az rest --method GET \\
                                        --uri "https://graph.microsoft.com/v1.0/users/${userId}/appRoleAssignments" \\
                                        | jq -r '.value[] | select(.resourceId=="${params.NEW_RELIC_SP_ID}") | .id // empty'
                                """,
                                returnStdout: true
                            ).trim()

                            if (yaEnNewRelic) {
                                echo "   ⏭️  Ya asignado a New Relic, se omite"
                            } else {
                                def assignResult = sh(
                                    script: """
                                        az rest --method POST \\
                                            --uri "https://graph.microsoft.com/v1.0/servicePrincipals/${params.NEW_RELIC_SP_ID}/appRoleAssignedTo" \\
                                            --body '{
                                                "principalId": "${userId}",
                                                "resourceId":  "${params.NEW_RELIC_SP_ID}",
                                                "appRoleId":   "${params.NEW_RELIC_ROLE_ID}"
                                            }' | jq -r '.id // empty'
                                    """,
                                    returnStdout: true
                                ).trim()

                                if (assignResult) {
                                    stats.newRelic++
                                    echo "   ✅ Asignado a New Relic correctamente"
                                } else {
                                    echo "   ❌ Error al asignar a New Relic para ${email}"
                                }
                            }
                        }

                        echo "   ✔️  OK\n"
                    }

                    echo """
                    ╔══════════════════════════════════╗
                    ║           RESUMEN FINAL          ║
                    ╠══════════════════════════════════╣
                    ║ 📨 Invitados nuevos  : ${stats.invited}
                    ║ 🔄 Ya existían       : ${stats.existing}
                    ║ 👥 Nuevos en grupo   : ${stats.newGroups}
                    ║ ⏭️  Ya en grupo       : ${stats.skipped}
                    ║ 🔷 Asignados New Relic: ${stats.newRelic}
                    ║ ❌ Fallidos          : ${stats.failed}
                    ╚══════════════════════════════════╝
                    """
                }
            }
        }
        stage('Logout') {
            when { expression { !params.DRY_RUN } }
            steps { sh 'az logout' }
        }
    }
    post {
        success {
            script {
                echo params.DRY_RUN ? '✅ Preview OK — no se realizaron cambios' : '🎉 Proceso completado: Guests ExternalAzureAD creados y asignados'
            }
        }
    }
}