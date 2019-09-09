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

<#-- See the mchile.humanteam.PayrollServices.get#EmployeeReceiptDisplayInfo service for data preparation -->

<#assign cellPadding = "1pt">
<#assign dateFormat = dateFormat!"dd MMM yyyy">
<#assign negOne = -1>
<#if original! == "true"><#assign showOrig = true><#else><#assign showOrig = false></#if>

<#macro encodeText textValue>${(Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(textValue!"", false))!""}</#macro>
<#macro itemHeader itemTypeDescription>
    <fo:table table-layout="fixed" width="7.5in" margin-top="0.2in"><fo:table-body>
    <fo:table-row font-size="8pt">
        <fo:table-cell padding="${cellPadding}" width="6in"><fo:block font-size="10pt" font-weight="bold"><@encodeText (itemTypeDescription)!""/></fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}" width="1.5in"><fo:block font-size="10pt" font-weight="bold" text-align="right">Valor</fo:block></fo:table-cell>
    </fo:table-row>
</#macro>
<#macro itemFooter itemTotalDescription itemTotal currencyUomId>
    <fo:table-row font-size="8pt" border-top="solid black">
        <fo:table-cell padding="${cellPadding}"><fo:block font-weight="bold"><@encodeText (itemTotalDescription)!""/></fo:block></fo:table-cell>
        <#if 0 &gt; itemTotal>
            <fo:table-cell padding="${cellPadding}"><fo:block font-weight="bold" text-align="right">(${ec.l10n.format(-itemTotal!0, '#,###')})</fo:block></fo:table-cell>
        <#else>
            <fo:table-cell padding="${cellPadding}"><fo:block font-weight="bold" text-align="right">${ec.l10n.format(itemTotal!0, '#,###')}</fo:block></fo:table-cell>
        </#if>
    </fo:table-row>
    </fo:table-body></fo:table>
</#macro>
<#macro itemRow invoiceItem currencyUomId>
    <#assign itemTypeEnum = invoiceItem.type!>
    <fo:table-row font-size="8pt" border-bottom="thin dotted black">
        <fo:table-cell padding="${cellPadding}">
            <fo:block><@encodeText (invoiceItem.description)!(itemTypeEnum.description)!""/></fo:block>
        </fo:table-cell>
        <#if 0 &gt; invoiceItem.amount>
            <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">(${ec.l10n.format(((invoiceItem.quantity!1) * (-invoiceItem.amount!0)), '#,###')})</fo:block></fo:table-cell>
        <#else>
            <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.format(((invoiceItem.quantity!1) * (invoiceItem.amount!0)), '#,###')}</fo:block></fo:table-cell>
        </#if>
    </fo:table-row>
</#macro>

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
            <fo:block-container absolute-position="absolute" top="0in" left="0in" width="7.5in">
                <#if logoImageLocation?has_content>
                    <fo:block text-align="left">
                        <fo:external-graphic src="${logoImageLocation}" content-height="0.5in" content-width="scale-to-fit" width="2in" scaling="uniform"/>
                        <fo:block font-size="14pt" text-align="center" font-weight="bold">Liquidación de Sueldo <@encodeText (timePeriodTypeDescription)!""/></fo:block>
                    </fo:block>
                <#else>
                    <fo:block font-size="14pt" text-align="center" font-weight="bold">Liquidación de Sueldo <@encodeText (timePeriodTypeDescription)!""/></fo:block>
                </#if>
            </fo:block-container>
        </fo:static-content>

        <#-- body starts 0.5in + 0.7in = 1.2in from top, 0.5in from left -->
        <fo:flow flow-name="xsl-region-body">
            <#list employeeReceiptList as employeeReceipt>
                <#list employeeReceipt.copyList as copy>
                <fo:block-container width="7.5in">
                    <fo:table table-layout="fixed" width="7.5in"><fo:table-body>
                        <fo:table-row>
                            <fo:table-cell padding="2pt" width="1.65in">
                                <fo:block font-weight="bold" font-size="12pt">Nombre Empleador:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="4.45in">
                                <fo:block font-size="12pt">${ec.resource.expand('PartyNameOnlyTemplate', null, employeeReceipt.employerPartyDetail)}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.35in">
                                <fo:block font-weight="bold" font-size="12pt">Rut:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="1in">
                                <fo:block font-size="12pt">${employeeReceipt.employerRut}</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body></fo:table>
                </fo:block-container>
                <fo:block-container width="7.5in" border-top-style="solid">
                    <fo:table table-layout="fixed" margin-top="5pt" margin-bottom="5pt" width="7.5in"><fo:table-body>
                        <fo:table-row>
                            <fo:table-cell padding="2pt" width="3.75in">
                                <fo:block font-weight="bold" font-size="12pt" margin-top="5pt">Datos del Trabajador y Período</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body></fo:table>
                    <fo:table table-layout="fixed" margin-top="5pt" margin-bottom="5pt" width="7.5in" border-bottom-style="double"><fo:table-body>
                        <fo:table-row>
                            <fo:table-cell padding="2pt" width="0.60in">
                                <fo:block font-weight="bold" text-align="right">Nombre:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="3.05in">
                                <fo:block>${ec.resource.expand('PartyNameOnlyTemplate', null, employeeReceipt.employeePartyDetail)}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="1.0in">
                                <fo:block font-weight="bold" text-align="right">RUT:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="1.0in">
                                <fo:block>${employeeReceipt.employeeRut}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.8in">
                                <fo:block font-weight="bold" text-align="right">Mes:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="1.05in">
                                <fo:block>${timePeriodMonth} ${timePeriodYear}</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell padding="2pt">
                                <fo:block font-weight="bold" text-align="right">Lugar:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block>${employeeReceipt.employmentFacilityName}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block font-weight="bold" text-align="right">Sueldo Base:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block>${ec.l10n.format(employeeReceipt.baseSalary, '#,###')}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.75in">
                                <fo:block font-weight="bold" text-align="right">Días Trab:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.75in">
                                <fo:block>${employeeReceipt.daysWorked}</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body></fo:table>
                </fo:block-container>

                <#if employeeReceipt.totalHaberesImponibles != 0>
                    <fo:block-container width="7.5in">
                        <@itemHeader "Haberes Imponibles"/>
                        <#list employeeReceipt.haberesImponibles as haberImponible>
                            <@itemRow haberImponible employeeReceipt.currencyUomId/>
                        </#list>
                        <@itemFooter "Total Haberes Imponibles" employeeReceipt.totalHaberesImponibles!0 employeeReceipt.currencyUomId/>
                    </fo:block-container>
                </#if>

                <#if employeeReceipt.totalHaberesNoImponibles != 0>
                    <fo:block-container width="7.5in">
                        <@itemHeader "Haberes No Imponibles"/>
                        <#list employeeReceipt.haberesNoImponibles as haberNoImponible>
                            <@itemRow haberNoImponible employeeReceipt.currencyUomId/>
                        </#list>
                        <@itemFooter "Total Haberes No Imponibles" employeeReceipt.totalHaberesNoImponibles!0 employeeReceipt.currencyUomId/>
                    </fo:block-container>
                </#if>

                <fo:table table-layout="fixed" width="7.5in" margin-top="0.2in"><fo:table-body>
                    <fo:table-row font-size="8pt" border-bottom-style="double" border-width="2pt">
                        <fo:table-cell padding="${cellPadding}" width="6in"><fo:block font-size="11pt" font-weight="bold">Total Haberes</fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" width="1.5in"><fo:block font-size="11pt"
                        font-weight="bold" text-align="right">${ec.l10n.format((employeeReceipt.totalHaberes!0), '#,###')}</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body></fo:table>

                <#if employeeReceipt.totalDescuentosPrevisionales != 0>
                    <fo:block-container width="7.5in">
                        <@itemHeader "Descuentos Previsionales"/>
                        <#list employeeReceipt.descuentosPrevisionales as descuento>
                            <@itemRow descuento employeeReceipt.currencyUomId/>
                        </#list>
                        <@itemFooter "Total Descuentos Previsionales" employeeReceipt.totalDescuentosPrevisionales!0 employeeReceipt.currencyUomId/>
                    </fo:block-container>
                </#if>

                <#if employeeReceipt.totalDescuentosOtros != 0>
                    <fo:block-container width="7.5in">
                        <@itemHeader "Otros Descuentos"/>
                        <#list employeeReceipt.descuentosOtros as descuento>
                            <@itemRow descuento employeeReceipt.currencyUomId/>
                        </#list>
                        <@itemFooter "Total Otros Descuentos" employeeReceipt.totalDescuentosOtros!0 employeeReceipt.currencyUomId/>
                    </fo:block-container>
                </#if>

                <fo:table table-layout="fixed" width="7.5in" margin-top="0.2in"><fo:table-body>
                    <fo:table-row font-size="8pt" border-bottom-style="double" border-width="2pt">
                        <fo:table-cell padding="${cellPadding}" width="6in"><fo:block font-size="11pt" font-weight="bold">Total Descuentos</fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" width="1.5in"><fo:block font-size="11pt"
                        font-weight="bold" text-align="right">(${ec.l10n.format((-employeeReceipt.totalDescuentos!0), '#,###')})</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body></fo:table>

                <fo:table table-layout="fixed" width="7.5in" margin-top="0.3in" border-style="double black" border-left-style="double" border-right-style="double" border-width="2pt"><fo:table-body>
                    <fo:table-row font-size="8pt">
                        <fo:table-cell padding="${cellPadding}" width="6in"><fo:block font-size="12pt" font-weight="bold">Saldo Líquido a Pagar</fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" width="1.5in"><fo:block font-size="12pt" font-weight="bold" text-align="right">${employeeReceipt.liquidoAPagar}</fo:block></fo:table-cell>
                    </fo:table-row>
                    <fo:table-row font-size="8pt" border-bottom="thin solid black">
                        <fo:table-cell padding="${cellPadding}" width="7.5in"><fo:block font-size="8pt">Son: ${employeeReceipt.liquidoAPagarPalabras} pesos.</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body></fo:table>

                <fo:block margin-top="0.3in">Certifico que he recibido de mi empleador, ${ec.resource.expand('PartyNameOnlyTemplate', null, employeeReceipt.employerPartyDetail)}, a mi total y entera satisfacción, el saldo líquido indicado en la presente liquidación, sin tener cargo ni cobro posterior alguno que hacer, por los conceptos de esta liquidación. </fo:block>

                <fo:table table-layout="fixed" width="7.5in" margin-top="0.5in"><fo:table-body>
                    <fo:table-row font-size="8pt">
                        <fo:table-cell padding="${cellPadding}" width="0.51in"><fo:block font-size="12pt" font-weight="bold">Fecha</fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" width="1.5in"><fo:block font-size="12pt" border-bottom="solid black">:</fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" width="2.5in"><fo:block font-size="12pt" font-weight="bold" text-align="right">Firma Trabajador </fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" width="2.9in"><fo:block font-size="12pt" border-bottom="solid black">:</fo:block></fo:table-cell>
                    </fo:table-row>
                </fo:table-body></fo:table>

                <fo:block break-after="page"/>

                </#list>

            </#list>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
