<#--
This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.

To the extent possible under law, the author(s) have dedicated all
copyright and related and neighboring rights to this software to the
public domain worldwide. This software is distributed without any
warranty.

You should have received a copy of the CC0 Public Domain Dedication
along with this software (see the LICENSE.md file). If not, see
<http://creativecommons.org/publicdomain/zero/1.0/>.
-->

<#assign cellPadding = "1pt">
<#assign dateFormat = dateFormat!"dd MMM yyyy">
<#assign negOne = -1>
<#if original! == "true"><#assign showOrig = true><#else><#assign showOrig = false></#if>

<#macro encodeText textValue>${(Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(textValue!"", false))!""}</#macro>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="10pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter-portrait" page-width="8.5in" page-height="11in"
                               margin-top="0.5in" margin-bottom="0.5in" margin-left="0.5in" margin-right="0.5in">
            <fo:region-body margin-top="0.7in" margin-bottom="0.6in"/>
            <fo:region-before extent="0.7in"/>
            <fo:region-after extent="0.5in"/>
        </fo:simple-page-master>
    </fo:layout-master-set>

    <fo:page-sequence master-reference="letter-portrait" id="mainSequence">
        <fo:static-content flow-name="xsl-region-before">
            <fo:block-container absolute-position="absolute" top=".5in" left="0in" width="7.5in">
                <#if logoImageLocation?has_content>
                    <fo:block text-align="left">
                        <fo:external-graphic src="${logoImageLocation}" content-height="0.5in" content-width="scale-to-fit" width="2in" scaling="uniform"/>
                        <fo:block font-size="20pt" text-align="center" font-weight="bold"><@encodeText (title)!""/></fo:block>
                    </fo:block>
                <#else>
                    <fo:block font-size="20pt" text-align="center" font-weight="bold"><@encodeText (title)!""/></fo:block>
                </#if>
            </fo:block-container>
        </fo:static-content>

        <#-- body starts 0.5in + 0.7in = 1.2in from top, 0.5in from left -->
        <fo:flow flow-name="xsl-region-body">
            <fo:block margin-top="0.3in" font-size="12pt">${ec.l10n.format(certificateDate, 'd\' de \'MMMM\' de \'yyyy') }</fo:block>
            <#if documentType == 'vacaciones'>
                <fo:block margin-top="0.3in" font-size="12pt">${ec.resource.expand('PartyNameOnlyTemplate', null, employeePartyDetail)}, Cédula de Identidad N° ${employeeRut} declara que hace uso de ${days} días hábiles de feriado legal con su empleador, ${ec.resource.expand('PartyNameOnlyTemplate', null, employerPartyDetail)}, RUT ${employerRut}, desde el día ${ec.l10n.format(fromDate, 'd\' de \'MMMM\' de \'yyyy')} hasta el día ${ec.l10n.format(thruDate, 'd\' de \'MMMM\' de \'yyyy')}, ambos días inclusive, lo que corresponde a ${days} días hábiles. </fo:block>
                <fo:block margin-top="0.3in" font-size="12pt">A fecha ${ec.l10n.format(fromDate, 'd\' de \'MMMM\' de \'yyyy')} ${ec.resource.expand('PartyNameOnlyTemplate', null, employeePartyDetail)} mantiene ${accruedAmount} días de feriado legal acumulado con su empleador, del cual se restarán los ${days} días hábiles tomados en esta ocasión, quedando en ${accruedAmount-days} días hábiles.</fo:block>
            <#elseif documentType == 'permisoSinGoce'>
                <fo:block margin-top="0.3in" font-size="12pt">${ec.resource.expand('PartyNameOnlyTemplate', null,
                employeePartyDetail)}, Cédula de Identidad N° ${employeeRut}, declara que hace uso de permiso sin goce
                de sueldo por ${days} días hábiles, entre el día ${ec.l10n.format(fromDate, 'd\' de \'MMMM\' de \'yyyy')} y el día ${ec.l10n.format(thruDate, 'd\' de \'MMMM\' de \'yyyy')}, ambos días inclusive, que serán descontados del sueldo de(l) (los) período(s) correspondiente(s) por parte el empleador,  ${ec.resource.expand('PartyNameOnlyTemplate', null, employerPartyDetail)}, RUT ${employerRut}.</fo:block>
            </#if>

                <fo:table table-layout="fixed" width="7.5in" margin-top="1in"><fo:table-body>
                    <fo:table-row font-size="8pt">
                        <fo:table-cell padding="${cellPadding}" width="2.9in"><fo:block font-size="12pt" border-bottom="solid black"></fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row font-size="8pt">
                        <fo:table-cell padding="${cellPadding}" width="2.5in"><fo:block font-size="12pt" font-weight="bold">${ec.resource.expand('PartyNameOnlyTemplate', null, employeePartyDetail)}</fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row font-size="8pt">
                        <fo:table-cell padding="${cellPadding}" width="2.5in"><fo:block font-size="12pt" font-weight="bold">RUT: ${employeeRut}</fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row font-size="8pt">
                        <fo:table-cell padding="${cellPadding}" width="2.5in"><fo:block font-size="12pt" font-weight="bold">Trabajador</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body></fo:table>

                <fo:block break-after="page"/>

        </fo:flow>
    </fo:page-sequence>
</fo:root>
