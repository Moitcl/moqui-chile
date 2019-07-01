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
<#macro productItemRow invoiceItem invoiceItemList itemIndex>
    <#assign childItemList = invoiceItemList.cloneList().filterByAnd("parentItemSeqId", invoiceItem.invoiceItemSeqId)>
    <#assign itemTypeEnum = invoiceItem.type!>
    <#assign product = invoiceItem.product!>
    <#assign asset = invoiceItem.asset!>
    <#assign lot = asset.lot!>
    <fo:table-row font-size="8pt" border-bottom="thin solid black">
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="center"><#if (itemIndex > 0)>${itemIndex}<#else> </#if></fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block>${(product.pseudoId)!(invoiceItem.productId)!""}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block>${(lot.lotNumber)!(asset.lotId)!""}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}">
            <fo:block><@encodeText (invoiceItem.description)!(itemTypeEnum.description)!""/></fo:block>
            <#if invoiceItem.otherPartyProductId?has_content><fo:block>Your Product: ${invoiceItem.otherPartyProductId}</fo:block></#if>
        </fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="center">${invoiceItem.quantity!"1"}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(invoiceItem.amount!0, invoice.currencyUomId, 3)}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(((invoiceItem.quantity!1) * (invoiceItem.amount!0)), invoice.currencyUomId, 3)}</fo:block></fo:table-cell>
    </fo:table-row>
    <#if childItemList?has_content><#list childItemList as childItem>
        <@productItemRow childItem invoiceItemList negOne/>
    </#list></#if>
</#macro>
<#macro otherItemRow invoiceItem invoiceItemList itemIndex>
    <#assign childItemList = invoiceItemList.cloneList().filterByAnd("parentItemSeqId", invoiceItem.invoiceItemSeqId)>
    <#assign itemTypeEnum = invoiceItem.type!>
    <#assign timeEntry = invoiceItem.findRelatedOne("mantle.work.time.TimeEntry", false, false)!>
    <#assign rateTypeEnum = ""><#assign workEffort = "">
    <#if timeEntry?has_content>
        <#assign rateTypeEnum = timeEntry.findRelatedOne("RateType#moqui.basic.Enumeration", true, false)!>
        <#assign workEffort = timeEntry.findRelatedOne("mantle.work.effort.WorkEffort", false, false)!>
    </#if>
    <fo:table-row font-size="8pt" border-bottom="thin solid black">
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="center"><#if (itemIndex > 0)>${itemIndex}<#else> </#if></fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block>${(itemTypeEnum.description)!""}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block>${ec.l10n.format(invoiceItem.itemDate, dateFormat)}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}">
            <fo:block><@encodeText invoiceItem.description!""/></fo:block>
            <#if (timeEntry.workEffortId)?has_content><fo:block>Task: ${timeEntry.workEffortId} - <@encodeText workEffort.workEffortName!""/></fo:block></#if>
            <#if rateTypeEnum?has_content><fo:block>Rate: ${rateTypeEnum.description}</fo:block></#if>
            <#if timeEntry?has_content><fo:block>${ec.l10n.format(timeEntry.fromDate, "dd MMM yyyy hh:mm")} to ${ec.l10n.format(timeEntry.thruDate, "dd MMM yyyy hh:mm")}, Break ${timeEntry.breakHours!"0"}h</fo:block></#if>
            <#if invoiceItem.otherPartyProductId?has_content><fo:block>Your Product: ${invoiceItem.otherPartyProductId}</fo:block></#if>
        </fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="center">${invoiceItem.quantity!"1"}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(invoiceItem.amount!0, invoice.currencyUomId, 3)}</fo:block></fo:table-cell>
        <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(((invoiceItem.quantity!1) * (invoiceItem.amount!0)), invoice.currencyUomId, 3)}</fo:block></fo:table-cell>
    </fo:table-row>
    <#if childItemList?has_content><#list childItemList as childItem>
        <@otherItemRow childItem invoiceItemList negOne/>
    </#list></#if>
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
                                <fo:block font-weight="bold" font-size="12pt" margin-top="5pt">Datos del Trabajador</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="3.75in">
                                <fo:block font-weight="bold" font-size="12pt" margin-top="5pt" text-align="right">Período de Remuneración</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body></fo:table>
                    <fo:table table-layout="fixed" margin-top="5pt" margin-bottom="25pt" width="7.5in" border-bottom-style="solid"><fo:table-body>
                        <fo:table-row>
                            <fo:table-cell padding="2pt" width="0.95in">
                                <fo:block font-weight="bold" text-align="right">RUT:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="4.85in">
                                <fo:block>${employeeReceipt.employeeRut}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="1.2in">
                                <fo:block font-weight="bold" text-align="right">Mes:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.5in">
                                <fo:block>${timePeriodMonth}</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell padding="2pt">
                                <fo:block font-weight="bold" text-align="right">Nombre:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block>${ec.resource.expand('PartyNameOnlyTemplate', null, employeeReceipt.employeePartyDetail)}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block font-weight="bold" text-align="right">Año:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block>${timePeriodYear}</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell padding="2pt">
                                <fo:block font-weight="bold" text-align="right">Sueldo Base:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt">
                                <fo:block>${ec.l10n.format(employeeReceipt.baseSalary, '#,###')}</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.75in">
                                <fo:block font-weight="bold" text-align="right">Días Trabajados:</fo:block>
                            </fo:table-cell>
                            <fo:table-cell padding="2pt" width="0.75in">
                                <fo:block>${employeeReceipt.daysWorked}</fo:block>
                            </fo:table-cell>
                        </fo:table-row>
                    </fo:table-body></fo:table>
                </fo:block-container>

                <#if hasProductItems && !hasTimeEntryItems>
                    <fo:table table-layout="fixed" width="100%">
                        <fo:table-header font-size="9pt" border-bottom="solid black">
                            <fo:table-cell width="0.4in" padding="${cellPadding}"><fo:block text-align="center">Item</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block>Product</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block>Lot</fo:block></fo:table-cell>
                            <fo:table-cell width="2.5in" padding="${cellPadding}"><fo:block>Description</fo:block></fo:table-cell>
                            <fo:table-cell width="0.6in" padding="${cellPadding}"><fo:block text-align="center">Qty</fo:block></fo:table-cell>
                            <fo:table-cell width="0.9in" padding="${cellPadding}"><fo:block text-align="right">Amount</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block text-align="right">Total</fo:block></fo:table-cell>
                        </fo:table-header>
                        <fo:table-body>
                            <#list topItemList as invoiceItem>
                                <#if !(invoiceItem.parentItemSeqId?has_content)><@productItemRow invoiceItem invoiceItemList invoiceItem_index+1/></#if>
                            </#list>
                            <fo:table-row font-size="9pt" border-top="solid black">
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">Total</fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">${ec.l10n.formatCurrency(noAdjustmentTotal, invoice.currencyUomId)}</fo:block></fo:table-cell>
                            </fo:table-row>
                        </fo:table-body>
                    </fo:table>
                <#else>
                    <fo:table table-layout="fixed" width="100%">
                        <fo:table-header font-size="9pt" border-bottom="solid black">
                            <fo:table-cell width="0.3in" padding="${cellPadding}"><fo:block text-align="center">Item</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block>Type</fo:block></fo:table-cell>
                            <fo:table-cell width="0.8in" padding="${cellPadding}"><fo:block>Date</fo:block></fo:table-cell>
                            <fo:table-cell width="2.8in" padding="${cellPadding}"><fo:block>Description</fo:block></fo:table-cell>
                            <fo:table-cell width="0.6in" padding="${cellPadding}"><fo:block text-align="center">Qty</fo:block></fo:table-cell>
                            <fo:table-cell width="0.9in" padding="${cellPadding}"><fo:block text-align="right">Amount</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block text-align="right">Total</fo:block></fo:table-cell>
                        </fo:table-header>
                        <fo:table-body>
                        <#list topItemList as invoiceItem>
                            <#if !(invoiceItem.parentItemSeqId?has_content)><@otherItemRow invoiceItem invoiceItemList invoiceItem_index+1/></#if>
                        </#list>
                            <fo:table-row font-size="9pt" border-top="solid black">
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">Total</fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">${ec.l10n.formatCurrency(noAdjustmentTotal, invoice.currencyUomId)}</fo:block></fo:table-cell>
                            </fo:table-row>
                        </fo:table-body>
                    </fo:table>
                </#if>

                <fo:table table-layout="fixed" width="100%" margin-top="0.1in">
                    <fo:table-header font-size="9pt" border-bottom="solid black">
                        <fo:table-cell width="2.0in" padding="${cellPadding}"><fo:block>Type</fo:block></fo:table-cell>
                        <#if hasTimeEntryItems><fo:table-cell width="1.2in" padding="${cellPadding}"><fo:block text-align="right">Amount</fo:block></fo:table-cell></#if>
                        <fo:table-cell width="1.0in" padding="${cellPadding}"><fo:block text-align="center">Qty</fo:block></fo:table-cell>
                        <fo:table-cell width="1.2in" padding="${cellPadding}"><fo:block text-align="right">Total</fo:block></fo:table-cell>
                    </fo:table-header>
                    <fo:table-body>
                    <#list itemTypeSummaryMapList as itemTypeSummaryMap>
                        <#assign itemTypeEnum = ec.entity.find("moqui.basic.Enumeration").condition("enumId", itemTypeSummaryMap.itemTypeEnumId).useCache(true).one()>
                        <fo:table-row font-size="9pt" border-bottom="thin solid black">
                            <fo:table-cell padding="${cellPadding}"><fo:block>${(itemTypeEnum.description)!""}</fo:block></fo:table-cell>
                            <fo:table-cell padding="${cellPadding}"><fo:block text-align="center">${itemTypeSummaryMap.quantity}</fo:block></fo:table-cell>
                            <#if hasTimeEntryItems><fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(itemTypeSummaryMap.amount, invoice.currencyUomId)}</fo:block></fo:table-cell></#if>
                            <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(itemTypeSummaryMap.total, invoice.currencyUomId)}</fo:block></fo:table-cell>
                        </fo:table-row>
                    </#list>
                    <fo:table-row font-size="9pt">
                        <fo:table-cell padding="${cellPadding}"><fo:block text-align="center"> </fo:block></fo:table-cell>
                        <fo:table-cell padding="${cellPadding}" font-weight="bold"><fo:block text-align="right">Total</fo:block></fo:table-cell>
                        <#if hasTimeEntryItems><fo:table-cell padding="${cellPadding}"><fo:block text-align="right"> </fo:block></fo:table-cell></#if>
                        <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">${ec.l10n.formatCurrency(noAdjustmentTotal, invoice.currencyUomId)}</fo:block></fo:table-cell>
                    </fo:table-row>
                    </fo:table-body>
                </fo:table>

                <#if !showOrig && adjustmentItemList?has_content>
                    <fo:table table-layout="fixed" width="100%" margin-top="0.1in">
                        <fo:table-header font-size="9pt" border-bottom="solid black">
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block text-align="center">Adjustment</fo:block></fo:table-cell>
                            <fo:table-cell width="4in" padding="${cellPadding}"><fo:block>Description</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block text-align="center">Date</fo:block></fo:table-cell>
                            <fo:table-cell width="1in" padding="${cellPadding}"><fo:block text-align="right">Amount</fo:block></fo:table-cell>
                        </fo:table-header>
                        <fo:table-body>
                            <#list adjustmentItemList as invoiceItem>
                                <#assign itemTypeEnum = invoiceItem.type!>
                                <fo:table-row font-size="8pt" border-bottom="thin solid black">
                                    <fo:table-cell padding="${cellPadding}"><fo:block text-align="center">${invoiceItem_index}</fo:block></fo:table-cell>
                                    <fo:table-cell padding="${cellPadding}">
                                        <fo:block><@encodeText (invoiceItem.description)!(itemTypeEnum.description)!""/></fo:block>
                                        <#if invoiceItem.otherPartyProductId?has_content><fo:block>Your Product: ${invoiceItem.otherPartyProductId}</fo:block></#if>
                                    </fo:table-cell>
                                    <fo:table-cell padding="${cellPadding}"><fo:block text-align="center">${ec.l10n.format(invoiceItem.itemDate!, dateFormat)}</fo:block></fo:table-cell>
                                    <fo:table-cell padding="${cellPadding}"><fo:block text-align="right">${ec.l10n.formatCurrency(((invoiceItem.quantity!1) * (invoiceItem.amount!0)), invoice.currencyUomId, 3)}</fo:block></fo:table-cell>
                                </fo:table-row>
                            </#list>
                            <fo:table-row font-size="9pt" border-top="solid black">
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">Adjustments Total</fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">${ec.l10n.formatCurrency(adjustmentTotal, invoice.currencyUomId)}</fo:block></fo:table-cell>
                            </fo:table-row>
                            <fo:table-row font-size="9pt" border-top="solid black">
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">Invoice Total</fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block> </fo:block></fo:table-cell>
                                <fo:table-cell padding="${cellPadding}"><fo:block text-align="right" font-weight="bold">${ec.l10n.formatCurrency(invoiceTotal, invoice.currencyUomId)}</fo:block></fo:table-cell>
                            </fo:table-row>
                        </fo:table-body>
                    </fo:table>
                </#if>
                <fo:block break-after="page"/>
            </#list>
        </fo:flow>
    </fo:page-sequence>
</fo:root>
