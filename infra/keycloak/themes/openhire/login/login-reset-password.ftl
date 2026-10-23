<#-- Adapted from Keycloak 26.8.0, Apache-2.0. Authentication behavior retained. -->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayInfo=true displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "form">
        <form id="kc-reset-password-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#assign label>
                <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
            </#assign>
            <@field.input name="username" label=label value=auth.attemptedUsername!'' autofocus=true />

            <@buttons.actionGroup>
              <@buttons.button id="kc-form-buttons" label="ohSendReset"/>

            </@buttons.actionGroup>

        </form>
    <#elseif section = "info" >
        <p class="oh-help">${msg("ohResetProviderHelp")}</p>
    </#if>
</@layout.registrationLayout>
