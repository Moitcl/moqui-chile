<?xml version="1.0" encoding="UTF-8"?>

<xsl:stylesheet version="1.1" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:fo="http://www.w3.org/1999/XSL/Format"
                xmlns:tedbarcode="cl.moit.dte.TedBarcodeExtension" extension-element-prefixes="tedbarcode"
                xmlns:fox="http://xmlgraphics.apache.org/fop/extensions"
                xmlns:str="http://exslt.org/strings"
                xmlns:siidte="http://www.sii.cl/SiiDte">

    <xsl:param name="fonoContacto"/>
    <xsl:param name="mailContacto"/>
    <xsl:param name="oficinaSII" select="'Santiago Oriente'"/>
    <xsl:param name="logo"/>
    <xsl:param name="cedible"/>
    <xsl:param name="numeroResolucionSii" select="'80'"/>
    <xsl:param name="fechaResolucionSii" select="'2014'"/>
    <xsl:param name="showItemNumber" select="'Y'"/>
    <xsl:param name="commentAfterDetalle" select="''"/>
    <xsl:param name="cuentaBancariaText" select="''"/>
    <xsl:param name="cuentaBancariaMail" select="''"/>
    <xsl:param name="maxRefs" select="10"/>
    <xsl:param name="maxItems" select="20"/>
    <xsl:param name="showExcessRefsInOtherPage" select="'Y'"/>
    <xsl:param name="showExcessItemsInOtherPage" select="'Y'"/>
    <xsl:param name="detailHeaderBgColor" select="'&#35;eaeaea'"/>
    <xsl:param name="detailHeaderFgColor" select="'&#35;ffffff'"/>
    <xsl:param name="detailHeaderSepColor" select="'&#35;ffffff'"/>
    <xsl:param name="tableBorderColor" select="'&#35;eaeaea'"/>
    <xsl:param name="vendorNameColor" select="'black'"/>
    <xsl:param name="rutEnviador" select="''"/>
    <xsl:param name="draft" select="'N'"/>
    <xsl:param name="unnamedCustomer" select="'N'"/>

    <xsl:output method="xml" version="1.0" omit-xml-declaration="no" indent="yes" encoding="UTF-8"/>

    <xsl:decimal-format name="us" decimal-separator='.' grouping-separator=','/>
    <xsl:decimal-format name="european" decimal-separator=',' grouping-separator='.'/>

    <xsl:param name="versionParam" select="'1.0'"/>
    <xsl:template match="/siidte:DTE/siidte:Documento | /DTE/Documento | /siidte:DTE/siidte:Liquidacion | /DTE/Liquidacion | /siidte:DTE/siidte:Exportaciones | /DTE/Exportaciones">
        <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
            <fo:layout-master-set>
                <fo:simple-page-master master-name="simple" page-height="27.9cm" page-width="21.6cm" margin-top="1cm" margin-bottom="2cm" margin-left="0.8cm" margin-right="1cm">
                    <fo:region-body margin-top="0cm" margin-bottom="1.2cm" background-repeat="no-repeat" fox:background-image-width="6cm"
                                    background-position-horizontal="0.49cm" background-position-vertical="0.27cm">
                        <!--
                        <xsl:if test="$draft = 'Y'">
                        -->
                            <xsl:attrbute name="background-image"><xsl:value-of select="'component://MoquiChile/DTE/TEMPLATES/borrador.svg'"/></xsl:attrbute>
                        <!--
                        </xsl:if>
                        -->
                    </fo:region-body>
                    <fo:region-before extent="3cm"/>
                    <fo:region-after extent="1.0cm"/>
                </fo:simple-page-master>
            </fo:layout-master-set>

            <xsl:variable name="tipo" select="siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE | Encabezado/IdDoc/TipoDTE"/>
            <xsl:variable name="folio" select="siidte:Encabezado/siidte:IdDoc/siidte:Folio | Encabezado/IdDoc/Folio"/>
            <xsl:if test="$draft = 'Y'"><xsl:variable name="folio" select="'SIN FOLIO'"/></xsl:if>
            <xsl:variable name="rutEmisor" select="siidte:Encabezado/siidte:Emisor/siidte:RUTEmisor | Encabezado/Emisor/RUTEmisor"/>
            <xsl:variable name="razonSocialEmisor" select="Encabezado/siidte:Emisor/siidte:RznSoc | Encabezado/Emisor/RznSoc | siidte:Encabezado/siidte:Emisor/siidte:RznSocEmisor | Encabezado/Emisor/RznSocEmisor"/>
            <fo:declarations>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                    <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <!-- Dublin Core properties go here -->
                            <dc:title><xsl:choose>
                                <xsl:when test="$tipo=33">Factura Electrónica</xsl:when>
                                <xsl:when test="$tipo=34">Factura Electrónica Exenta</xsl:when>
                                <xsl:when test="$tipo=39">Boleta Electrónica</xsl:when>
                                <xsl:when test="$tipo=41">Boleta Electrónica Exenta</xsl:when>
                                <xsl:when test="$tipo=52">Guía de Despacho Electrónica</xsl:when>
                                <xsl:when test="$tipo=56">Nota de Débito Electrónica</xsl:when>
                                <xsl:when test="$tipo=61">Nota de Crédito Electrónica</xsl:when>
                                <xsl:when test="$tipo=110">Factura de Exportación Electrónica</xsl:when>
                                <xsl:when test="$tipo=111">Nota de Débito Exportación Electrónica</xsl:when>
                                <xsl:when test="$tipo=112">Nota de Crédito Exportación Electrónica</xsl:when>
                                <xsl:otherwise>DTE tipo <xsl:value-of select="$tipo"/></xsl:otherwise>
                            </xsl:choose> Folio <xsl:value-of select="$folio"/> RUT <xsl:value-of select="$rutEmisor"/></dc:title>
                            <dc:creator><xsl:value-of select="$razonSocialEmisor"/></dc:creator>
                            <dc:description>Representación impresa de Documento Tributario Electrónico (DTE) de acuerdo a las definiciones del Servicio de Impuestos Internos de Chile</dc:description>
                        </rdf:Description>
                        <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                            <!-- XMP properties go here -->
                            <xmp:CreatorTool>Moit ERP https://moit.cl/</xmp:CreatorTool>
                        </rdf:Description>
                    </rdf:RDF>
                </x:xmpmeta>
            </fo:declarations>

            <fo:page-sequence master-reference="simple">
                <fo:flow flow-name="xsl-region-body">
                    <xsl:call-template name="Documento"/>
                </fo:flow>
            </fo:page-sequence>
            <xsl:if test="(count(siidte:Detalle | Detalle) > $maxItems and $showExcessItemsInOtherPage = 'Y') or (count(siidte:Referencia | Referencia) > $maxRefs and $showExcessRefsInOtherPage = 'Y')">
                <fo:page-sequence master-reference="simple" id="adjunto">
                    <fo:static-content flow-name="xsl-region-after">
                        <fo:block text-align="center" font-size="8.4pt" language="es" font-family="Helvetica, Arial, sans-serif">
                            <xsl:text>Página </xsl:text><fo:page-number/><xsl:text> de </xsl:text>
                            <fo:page-number-citation-last ref-id="adjunto"/>
                        </fo:block>
                    </fo:static-content>
                    <fo:flow flow-name="xsl-region-body">
                        <xsl:call-template name="ExceedingItemsAndRefs"/>
                    </fo:flow>
                </fo:page-sequence>
            </xsl:if>
        </fo:root>
    </xsl:template>

    <xsl:template name="Documento">
        <fo:block>
            <fo:block-container absolute-position="absolute" top="0cm" left="0cm">
                <xsl:apply-templates select="siidte:Encabezado/siidte:Emisor | Encabezado/Emisor">
                    <xsl:with-param name="folio">
                        <xsl:choose>
                            <xsl:when test="$draft = 'Y'">
                                <xsl:value-of select="'SIN FOLIO'"/>
                            </xsl:when>
                            <xsl:otherwise>
                                <xsl:value-of select="siidte:Encabezado/siidte:IdDoc/siidte:Folio | Encabezado/IdDoc/Folio"/>
                            </xsl:otherwise>
                        </xsl:choose></xsl:with-param>
                    <xsl:with-param name="tipo"><xsl:value-of select="siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE | Encabezado/IdDoc/TipoDTE"/></xsl:with-param>
                </xsl:apply-templates>
                <xsl:apply-templates select="siidte:Encabezado/siidte:Receptor | Encabezado/Receptor">
                    <xsl:with-param name="fecha"><xsl:value-of select="siidte:Encabezado/siidte:IdDoc/siidte:FchEmis | Encabezado/IdDoc/FchEmis"/></xsl:with-param>
                    <xsl:with-param name="medioPago"><xsl:value-of select="siidte:Encabezado/siidte:IdDoc/siidte:MedioPago | Encabezado/IdDoc/MedioPago"/></xsl:with-param>
                    <xsl:with-param name="formaPago"><xsl:value-of select="siidte:Encabezado/siidte:IdDoc/siidte:FmaPago | Encabezado/IdDoc/FmaPago"/></xsl:with-param>
                    <xsl:with-param name="rutReceptor"><xsl:value-of select="siidte:Encabezado/siidte:Receptor/siidte:RUTRecep | Encabezado/Receptor/RUTRecep"/></xsl:with-param>
                </xsl:apply-templates>

                <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                          fox:border-radius="4pt" border-width="0.8pt" border-style="solid">
                    <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                    <fo:block-container>
                        <xsl:if test="count(siidte:Detalle | Detalle) &lt; 8">
                            <xsl:attribute name="height">11cm</xsl:attribute>
                        </xsl:if>
                        <fo:table table-layout="fixed" width="100%" border-collapse="collapse">
                            <xsl:call-template name="ItemColumnsAndHeader"/>
                                <fo:table-body>
                                <xsl:choose>
                                    <xsl:when test="siidte:Detalle | Detalle">
                                        <xsl:apply-templates select="siidte:Detalle[position() &lt;= $maxItems] | Detalle[position() &lt;= $maxItems]"/>
                                        <xsl:if test="count(siidte:Detalle | Detalle) > $maxItems">
                                            <fo:table-row>
                                                <fo:table-cell number-columns-spanned="5"><fo:block font-weight="bold">Más <xsl:value-of select="count(siidte:Detalle | Detalle)-$maxItems"/> ítemes <xsl:choose>
                                                    <xsl:when test="$showExcessItemsInOtherPage = 'Y'"><xsl:value-of select="'detallados en adjunto'"/></xsl:when>
                                                    <xsl:otherwise><xsl:value-of select="'no mostrados'"/></xsl:otherwise>
                                                </xsl:choose></fo:block></fo:table-cell>
                                            </fo:table-row>
                                        </xsl:if>
                                    </xsl:when>
                                    <xsl:otherwise>
                                        <fo:table-row>
                                            <xsl:if test="$showItemNumber = 'Y'">
                                                <fo:table-cell><fo:block>-</fo:block></fo:table-cell>
                                            </xsl:if>
                                            <fo:table-cell><fo:block>-</fo:block></fo:table-cell>
                                        </fo:table-row>
                                    </xsl:otherwise>
                                </xsl:choose>

                            </fo:table-body>
                        </fo:table>
                    </fo:block-container>
                </fo:block>

                <xsl:if test="not ($commentAfterDetalle = '')">
                    <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                              fox:border-radius="4pt" border-width="0.8pt" border-style="solid">
                        <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                        <fo:table table-layout="fixed" margin="2pt" border-collapse="collapse"><fo:table-body><fo:table-cell>
                            <xsl:for-each select="str:tokenize($commentAfterDetalle , '&#xA;' )">
                                <fo:block margin="2pt" font-size="8.4pt" font-family="Helvetica, Arial, sans-serif"><xsl:value-of select="."/></fo:block>
                            </xsl:for-each>
                        </fo:table-cell></fo:table-body></fo:table>
                    </fo:block>
                </xsl:if>
                <xsl:if test="siidte:DscRcgGlobal | DscRcgGlobal">
                    <xsl:call-template name="DscRcgGlobalTable"/>
                </xsl:if>

                <fo:table table-layout="fixed">
                    <fo:table-column column-width="14.2cm"/>
                    <fo:table-column column-width="5.6cm"/>
                    <fo:table-body><fo:table-row>
                        <fo:table-cell><xsl:choose>
                            <xsl:when test="siidte:Referencia | Referencia">
                                <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                          fox:border-radius="4pt" border-width="0.8pt" border-style="solid" margin-right="2pt">
                                    <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                                    <fo:table table-layout="fixed" margin="2pt">
                                        <xsl:call-template name="ReferencesColumnsAndHeader"/>

                                        <fo:table-body>
                                            <xsl:apply-templates select="siidte:Referencia[position() &lt;= $maxRefs] | Referencia[position() &lt;= $maxRefs]"/>
                                            <xsl:if test="count(siidte:Referencia | Referencia) > $maxRefs">
                                                <fo:table-row>
                                                    <fo:table-cell number-columns-spanned="5"><fo:block font-weight="bold">Más <xsl:value-of select="count(siidte:Referencia | Referencia)-$maxRefs"/> referencias <xsl:choose>
                                                        <xsl:when test="$showExcessRefsInOtherPage = 'Y'"><xsl:value-of select="'en adjunto'"/></xsl:when>
                                                        <xsl:otherwise><xsl:value-of select="'no mostradas'"/></xsl:otherwise>
                                                    </xsl:choose></fo:block></fo:table-cell>
                                                </fo:table-row>
                                            </xsl:if>
                                        </fo:table-body>
                                    </fo:table>
                                </fo:block>
                            </xsl:when>
                            <xsl:otherwise>
                                <fo:block/>
                            </xsl:otherwise></xsl:choose>
                            <xsl:choose>
                                <xsl:when test="$cedible = 'true'">
                                    <fo:block font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                              fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" margin-right="2pt">
                                        <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                                        <fo:table table-layout="fixed" width="100%">
                                            <fo:table-column column-width="50%"/>
                                            <fo:table-column column-width="50%"/>
                                            <fo:table-header background-color="#eaeaea"><fo:table-row>
                                                <fo:table-cell number-columns-spanned="2"><fo:block font-weight="bold" text-align="center" margin="2pt">ACUSE DE RECIBO</fo:block></fo:table-cell>
                                            </fo:table-row></fo:table-header>
                                            <fo:table-body>
                                                <fo:table-row border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea">
                                                    <fo:table-cell><fo:block font-weight="bold" margin="4pt">Nombre:</fo:block></fo:table-cell><fo:table-cell><fo:block font-weight="bold" margin="4pt">RUT:</fo:block></fo:table-cell></fo:table-row>
                                                <fo:table-row>
                                                    <fo:table-cell border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea"><fo:block font-weight="bold" margin="4pt">Fecha:</fo:block></fo:table-cell><fo:table-cell><fo:block font-weight="bold" margin="4pt">Firma:</fo:block></fo:table-cell></fo:table-row>
                                                <fo:table-row border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea">
                                                    <fo:table-cell><fo:block font-weight="bold" margin="4pt" margin-bottom="2pt">Recinto:</fo:block></fo:table-cell></fo:table-row>
                                                <fo:table-row>
                                                    <fo:table-cell number-columns-spanned="2"><fo:block font-size="7pt" margin="4pt">El acuse de recibo que se declara en este acto, de acuerdo a lo dispuesto en la letra b) del Art. 4° y la letra c) del Art. 5° de la Ley 19.983, acredita que la entrega de mercadería(s) o servicio(s) prestado(s) ha(n) sido recibido(s).</fo:block></fo:table-cell></fo:table-row>
                                            </fo:table-body>
                                        </fo:table>
                                    </fo:block>
                                    <fo:block text-align="right" font-size="9.01pt"><xsl:choose><xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=52) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=52)">CEDIBLE CON SU FACTURA&#160;&#160;</xsl:when><xsl:otherwise>CEDIBLE&#160;&#160;</xsl:otherwise></xsl:choose></fo:block>
                                </xsl:when>
                                <xsl:otherwise>
                                    <fo:block/>
                                </xsl:otherwise></xsl:choose>

                            <xsl:if test="$cuentaBancariaText">
                                <fo:block margin-top="1cm" font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                          fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" margin-right="2pt">
                                    <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                                    <fo:table table-layout="fixed">
                                        <fo:table-column column-width="100%"/>
                                        <fo:table-header background-color="#eaeaea"><fo:table-row>
                                            <fo:table-cell><fo:block font-size="9.01pt" text-align="center" margin="4pt">Formas de pago:</fo:block></fo:table-cell>
                                        </fo:table-row></fo:table-header>
                                        <fo:table-body>
                                            <fo:table-row>
                                                <fo:table-cell>
                                                    <fo:block font-weight="bold" margin-left="4pt" margin-top="4pt">Transferencia bancaria</fo:block>
                                                    <fo:block margin-left="4pt" hyphenate="false"><xsl:value-of select="$cuentaBancariaText"/>
                                                        <xsl:if test="$cuentaBancariaMail">
                                                            <xsl:value-of select="' '"/>
                                                            <fo:basic-link><xsl:attribute name="external-destination">mailto:<xsl:value-of select="$cuentaBancariaMail"/></xsl:attribute><xsl:value-of select="$cuentaBancariaMail"/></fo:basic-link>
                                                        </xsl:if>
                                                    </fo:block>
                                                </fo:table-cell>
                                            </fo:table-row>
                                        </fo:table-body>
                                    </fo:table>
                                </fo:block>
                            </xsl:if>

                        </fo:table-cell>
                        <fo:table-cell>
                            <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                      fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" margin-left="2pt">
                                <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                                <fo:table table-layout="fixed">
                                    <fo:table-column column-width="3cm"/>
                                    <fo:table-column column-width="2.4cm"/>
                                    <fo:table-body>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">Monto Neto</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="siidte:Encabezado/siidte:Totales/siidte:MntNeto | Encabezado/Totales/MntNeto">$ <xsl:value-of select="format-number(siidte:Encabezado/siidte:Totales/siidte:MntNeto | Encabezado/Totales/MntNeto, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">Monto Exento</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="siidte:Encabezado/siidte:Totales/siidte:MntExe | Encabezado/Totales/MntExe">$ <xsl:value-of select="format-number(siidte:Encabezado/siidte:Totales/siidte:MntExe | Encabezado/Totales/MntExe, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">IVA <xsl:choose><xsl:when test="siidte:Encabezado/siidte:Totales/siidte:TasaIVA | Encabezado/Totales/TasaIVA"><xsl:value-of select="format-number(siidte:Encabezado/siidte:Totales/siidte:TasaIVA | Encabezado/Totales/TasaIVA, '###.###,##', 'european')"/>%</xsl:when></xsl:choose></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="siidte:Encabezado/siidte:Totales/siidte:IVA | Encabezado/Totales/IVA">$ <xsl:value-of select="format-number(siidte:Encabezado/siidte:Totales/siidte:IVA | Encabezado/Totales/IVA, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt" font-size="9.01pt">Total</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt" font-size="9.01pt"><xsl:choose>
                                                <xsl:when test="siidte:Encabezado/siidte:Totales/siidte:MntTotal | Encabezado/Totales/MntTotal">$ <xsl:value-of select="format-number(siidte:Encabezado/siidte:Totales/siidte:MntTotal | Encabezado/Totales/MntTotal, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                    </fo:table-body>
                                </fo:table>
                            </fo:block>
                            <xsl:apply-templates select="siidte:TED | TED"/>
                        </fo:table-cell>
                    </fo:table-row></fo:table-body>
                </fo:table>


            </fo:block-container>

        </fo:block>
        <fo:block-container absolute-position="absolute" left="19.85cm" top="-7.8cm" reference-orientation="90">
            <fo:block writing-mode="tb-rl" font-size="6pt" color="#c0c0c0"><fo:basic-link external-destination="https://moit.cl/">Moit ERP www.moit.cl</fo:basic-link></fo:block>
        </fo:block-container>
    </xsl:template>

    <!-- Datos del emisor -->
    <xsl:template match="siidte:Emisor | Emisor">
        <xsl:param name="folio"/>
        <xsl:param name="tipo"/>

        <fo:table table-layout="fixed">
            <fo:table-column column-width="3.2cm"/>
            <fo:table-column column-width="9.8cm"/>
            <fo:table-column column-width="6.8cm"/>
            <fo:table-body>
                <fo:table-cell>
                    <!--  El logo -->
                    <fo:block-container absolute-position="auto">
                        <xsl:choose>
                            <xsl:when test="$logo">
                                <fo:block><fo:external-graphic src="$logo" width="3cm" content-height="2.2cm" content-width="scale-to-fit" scaling="uniform">
                                    <xsl:attribute name="src">url(<xsl:value-of select="$logo"/>)</xsl:attribute></fo:external-graphic>
                                </fo:block>
                            </xsl:when>
                            <xsl:otherwise>
                                <fo:block/>
                            </xsl:otherwise>
                        </xsl:choose>
                    </fo:block-container>
                </fo:table-cell>

                <fo:table-cell>
                    <fo:block-container absolute-position="auto" left="3.1cm" top="0cm" width="9.75cm">

                        <fo:block font-size="10.81pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" text-align="left">
                            <xsl:attribute name="color"><xsl:value-of select="$vendorNameColor"/></xsl:attribute>
                            <xsl:value-of select="siidte:RznSoc | RznSoc | siidte:RznSocEmisor | RznSocEmisor"/>
                        </fo:block>

                        <xsl:if test="siidte:Sucursal | Sucursal">
                            <fo:block font-weight="bold" font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                                Sucursal: <xsl:value-of select="siidte:Sucursal | Sucursal"/> (Codigo SII: <xsl:value-of select="siidte:CdgSIISucur | CdgSIISucur"/>)
                            </fo:block>
                        </xsl:if>

                        <fo:block font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                            <xsl:value-of select="siidte:GiroEmis | GiroEmis | siidte:GiroEmisor | GiroEmisor"/>
                        </fo:block>

                        <fo:block font-size="8.4pt" margin-top="1mm" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                            Casa Matriz: <xsl:value-of select="siidte:DirOrigen | DirOrigen"/>, <xsl:value-of select="siidte:CmnaOrigen | CmnaOrigen"/>
                        </fo:block>

                        <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                            <xsl:if test="$fonoContacto">
                                Fono: <fo:basic-link><xsl:attribute name="external-destination">tel:<xsl:value-of select="$fonoContacto"/></xsl:attribute><xsl:value-of select="$fonoContacto"/></fo:basic-link>
                            </xsl:if>
                            <xsl:if test="$fonoContacto and $mailContacto"> -- </xsl:if>
                            <xsl:if test="$mailContacto">
                                Email: <fo:basic-link><xsl:attribute name="external-destination">mailto:<xsl:value-of select="$mailContacto"/></xsl:attribute><xsl:value-of select="$mailContacto"/></fo:basic-link>
                            </xsl:if>
                            <xsl:if test="not($fonoContacto) and not($mailContacto)">
                                <fo:block/>
                            </xsl:if>
                        </fo:block>

                    </fo:block-container>
                </fo:table-cell>

                <fo:table-cell>

                    <!-- Recuadro con folio -->
                    <fo:block-container absolute-position="auto" text-align="center" top="0cm" border-color="red" border-style="solid" border-width="0.8mm">
                        <fo:table table-layout="fixed"><fo:table-body>
                            <fo:table-row><fo:table-cell display-align="center">
                                <fo:block font-size="10.21pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" color="red" text-align="center" hyphenate="false">R.U.T.: <xsl:call-template name="RutFormat">
                                    <xsl:with-param name="rut"><xsl:value-of select="siidte:RUTEmisor | RUTEmisor"/></xsl:with-param></xsl:call-template>
                                </fo:block>
                                <fo:block font-size="10.21pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" color="red" text-align="center">
                                    <xsl:choose>
                                        <xsl:when test="$tipo=33">FACTURA ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=34">FACTURA ELECTRÓNICA EXENTA</xsl:when>
                                        <xsl:when test="$tipo=39">BOLETA ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=41">BOLETA ELECTRÓNICA EXENTA</xsl:when>
                                        <xsl:when test="$tipo=43">LIQUIDACIÓN FACTURA ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=46">FACTURA DE COMPRA ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=52">GUIA DE DESPACHO ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=56">NOTA DE DÉBITO ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=61">NOTA DE CRÉDITO ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=110">FACTURA DE EXPORTACIÓN ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=112">NOTA DE CRÉDITO EXPORTACIÓN ELECTRÓNICA</xsl:when>
                                        <xsl:otherwise>TIPO DTE NO SOPORTADO EN TEMPLATE XSL</xsl:otherwise>
                                    </xsl:choose>
                                </fo:block>
                                <fo:block font-size="10.21pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" color="red" text-align="center">N&#176;<xsl:value-of select="$folio"/></fo:block>
                            </fo:table-cell></fo:table-row></fo:table-body>
                        </fo:table>
                    </fo:block-container>
                    <fo:block-container absolute-position="auto" margin-top="0.1cm">
                        <fo:block font-size="10.21pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" color="red" text-align="center">S.I.I. - <xsl:value-of select="$oficinaSII"/></fo:block>
                    </fo:block-container>
                </fo:table-cell>
            </fo:table-body>
        </fo:table>

    </xsl:template>

    <!-- Datos del receptor -->
    <xsl:template match="siidte:Receptor | Receptor">
        <xsl:param name="fecha"/>
        <xsl:param name="medioPago"/>
        <xsl:param name="formaPago"/>
        <xsl:param name="rutReceptor"/>

        <fo:block-container absolute-position="auto">
            <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                      fox:border-radius="4pt" border-width="0.8pt"  border-style="solid">
                <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                <fo:table table-layout="fixed" margin="2pt">
                    <fo:table-column column-width="2.2cm"/>
                    <fo:table-column column-width="9cm"/>
                    <fo:table-column column-width="2cm"/>
                    <fo:table-column column-width="6.6cm"/>
                    <fo:table-body>
                        <xsl:choose>
                            <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=39) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=39) or (/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=41) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=41)">
                                <!-- Boleta -->
                                <xsl:choose>
                                    <xsl:when test="$unnamedCustomer = 'Y'">
                                        <!-- Sin identificación de Receptor -->
                                        <fo:table-row>
                                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Fecha</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:call-template name="FechaFormat"><xsl:with-param name="fecha"><xsl:value-of select="$fecha"/></xsl:with-param></xsl:call-template></fo:inline></fo:block></fo:table-cell>
                                        </fo:table-row>
                                    </xsl:when>
                                    <xsl:otherwise>
                                        <!-- Con Receptor -->
                                        <fo:table-row>
                                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Fecha</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:call-template name="FechaFormat"><xsl:with-param name="fecha"><xsl:value-of select="$fecha"/></xsl:with-param></xsl:call-template></fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Dirección</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:DirRecep | DirRecep"/></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Señor(es)</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:value-of select="siidte:RznSocRecep | RznSocRecep"/></fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Comuna</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:CmnaRecep | CmnaRecep"/></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">RUT</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt">
                                                <xsl:choose>
                                                    <xsl:when test="siidte:CdgIntRecep | CdgIntRecep">
                                                        <fo:table>
                                                            <fo:table-column column-width="30%"/>
                                                            <fo:table-column column-width="26%"/>
                                                            <fo:table-column column-width="45%"/>
                                                            <fo:table-body>
                                                                <fo:table-row>
                                                                    <fo:table-cell>
                                                                        <fo:block margin-left="0pt">
                                                                            <xsl:call-template name="RutFormat">
                                                                                <xsl:with-param name="rut"><xsl:value-of select="siidte:RUTRecep | RUTRecep"/></xsl:with-param>
                                                                            </xsl:call-template>
                                                                        </fo:block>
                                                                    </fo:table-cell>
                                                                    <fo:table-cell><fo:block font-weight="bold">Código Interno</fo:block></fo:table-cell>
                                                                    <fo:table-cell><fo:block><xsl:value-of select="siidte:CdgIntRecep | CdgIntRecep"/></fo:block></fo:table-cell>
                                                                </fo:table-row>
                                                            </fo:table-body>
                                                        </fo:table>
                                                    </xsl:when>
                                                    <xsl:otherwise>
                                                        <xsl:call-template name="RutFormat">
                                                            <xsl:with-param name="rut"><xsl:value-of select="siidte:RUTRecep | RUTRecep"/></xsl:with-param>
                                                        </xsl:call-template>
                                                    </xsl:otherwise>
                                                </xsl:choose>
                                            </fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Ciudad</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:CiudadRecep | CiudadRecep"/></fo:block></fo:table-cell>
                                        </fo:table-row>
                                    </xsl:otherwise>
                                </xsl:choose>
                            </xsl:when>
                            <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=110) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=110)">
                                <!-- Factura de Exportación -->
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Señor(es)</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:value-of select="siidte:RznSocRecep | RznSocRecep"/></fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Fecha</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:call-template name="FechaFormat"><xsl:with-param name="fecha"><xsl:value-of select="$fecha"/></xsl:with-param></xsl:call-template></fo:inline></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">RUT</fo:inline></fo:block></fo:table-cell>
                                    <xsl:call-template name="RutFormat">
                                        <xsl:with-param name="rut"><xsl:value-of select="siidte:RUTRecep | RUTRecep"/></xsl:with-param>
                                    </xsl:call-template>
                                    <fo:table-cell><fo:block font-weight="bold">Código Interno</fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block><xsl:value-of select="siidte:CdgIntRecep | CdgIntRecep"/></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Dirección</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:DirRecep | DirRecep"/></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Ciudad</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:CiudadRecep | CiudadRecep"/></fo:block></fo:table-cell>
                                </fo:table-row>
                                <xsl:if test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:OtraMoneda | /DTE/Documento/Encabezado/OtraMoneda)">
                                    <fo:table-row>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Tipo Moneda</fo:inline></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:OtraMoneda/siidte:TpoMoneda | /DTE/Documento/Encabezado/OtraMoneda/TpoMoneda"/></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Tipo Cambio</fo:inline></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:OtraMoneda/siidte:TpoCambio | /DTE/Documento/Encabezado/OtraMoneda/TpoCambio"/></fo:block></fo:table-cell>
                                    </fo:table-row>
                                </xsl:if>
                                <xsl:if test="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:CodPtoEmbarque | /DTE/Documento/Aduana/CodPtoEmbarque | /siidte:DTE/siidte:Documento/siidte:Aduana/siidte:CodPtoDesemb | /DTE/Documento/Aduana/CodPtoDesemb)">
                                    <fo:table-row>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Puerto Embarque</fo:inline></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt">
                                            <xsl:call-template name="CodigoPuertoDetail"><xsl:with-param name="codigoPuerto"><xsl:value-of select="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:CodPtoEmbarque | /DTE/Documento/Aduana/CodPtoEmbarque)"/></xsl:with-param></xsl:call-template>
                                            <xsl:if test="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:IdAdicPtoEmb | /DTE/Documento/Aduana/IdAdicPtoEmb)">
                                                (<xsl:value-of select="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:IdAdicPtoEmb | /DTE/Documento/Aduana/IdAdicPtoEmb)"/>)
                                            </xsl:if>
                                        </fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Puerto Desembarque</fo:inline></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt">
                                            <xsl:call-template name="CodigoPuertoDetail"><xsl:with-param name="codigoPuerto"><xsl:value-of select="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:CodPtoDesemb | /DTE/Documento/Aduana/CodPtoDesemb)"/></xsl:with-param></xsl:call-template>
                                            <xsl:if test="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:IdAdicPtoDesemb | /DTE/Documento/Aduana/IdAdicPtoDesemb)">
                                                (<xsl:value-of select="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:IdAdicPtoDesemb | /DTE/Documento/Aduana/IdAdicPtoDesemb)"/>)
                                            </xsl:if>
                                        </fo:block></fo:table-cell>
                                    </fo:table-row>
                                </xsl:if>
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Booking</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:Booking | /DTE/Documento/Aduana/Booking"/></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Operador</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:Operador | /DTE/Documento/Aduana/Operador"/></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Total Bultos</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:TotBultos | /DTE/Documento/Aduana/TotBultos"/></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Modalidad Venta</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt">
                                        <xsl:call-template name="CodigoModalidadVenta"><xsl:with-param name="codigoModalidadVenta"><xsl:value-of select="(/siidte:DTE/siidte:Documento/siidte:Aduana/siidte:CodModVenta | /DTE/Documento/Aduana/CodModVenta)"/></xsl:with-param></xsl:call-template>
                                    </fo:block></fo:table-cell>
                                </fo:table-row>
                            </xsl:when>
                            <xsl:otherwise>
                                <!-- Otros DTE -->
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Señor(es)</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:value-of select="siidte:RznSocRecep | RznSocRecep"/></fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Fecha</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:call-template name="FechaFormat"><xsl:with-param name="fecha"><xsl:value-of select="$fecha"/></xsl:with-param></xsl:call-template></fo:inline></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">RUT</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt">
                                        <xsl:choose>
                                            <xsl:when test="siidte:CdgIntRecep | CdgIntRecep">
                                                <fo:table>
                                                    <fo:table-column column-width="30%"/>
                                                    <fo:table-column column-width="26%"/>
                                                    <fo:table-column column-width="45%"/>
                                                    <fo:table-body>
                                                        <fo:table-row>
                                                            <fo:table-cell>
                                                                <fo:block margin-left="0pt">
                                                                <xsl:call-template name="RutFormat">
                                                                    <xsl:with-param name="rut"><xsl:value-of select="siidte:RUTRecep | RUTRecep"/></xsl:with-param>
                                                                </xsl:call-template>
                                                                </fo:block>
                                                            </fo:table-cell>
                                                            <fo:table-cell><fo:block font-weight="bold">Código Interno</fo:block></fo:table-cell>
                                                            <fo:table-cell><fo:block><xsl:value-of select="siidte:CdgIntRecep | CdgIntRecep"/></fo:block></fo:table-cell>
                                                        </fo:table-row>
                                                    </fo:table-body>
                                                </fo:table>
                                            </xsl:when>
                                            <xsl:otherwise>
                                                <xsl:call-template name="RutFormat">
                                                    <xsl:with-param name="rut"><xsl:value-of select="siidte:RUTRecep | RUTRecep"/></xsl:with-param>
                                                </xsl:call-template>
                                            </xsl:otherwise>
                                        </xsl:choose>
                                    </fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Dirección</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:DirRecep | DirRecep"/></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Giro</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:GiroRecep | GiroRecep"/></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Comuna</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:CmnaRecep | CmnaRecep"/></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <xsl:choose><xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=52) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=52)">
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Tipo de Traslado</fo:inline></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline>
                                            <xsl:choose>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=1) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=1)">Operación constituye venta</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=2) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=2)">Ventas por efectuar</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=3) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=3)">Consignaciones</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=4) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=4)">Entrega gratuita</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=5) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=5)">Traslados internos</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=6) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=6)">Otros traslados no venta</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=7) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=7)">Guía de devolución</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=8) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=8)">Traslado para exportación (no venta)</xsl:when>
                                                <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:IndTraslado=9) or (/DTE/Documento/Encabezado/IdDoc/IndTraslado=9)">Venta para exportación</xsl:when>
                                                <xsl:otherwise>Desconocido</xsl:otherwise>
                                            </xsl:choose>
                                        </fo:inline></fo:block></fo:table-cell>
                                    </xsl:when><xsl:otherwise>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Contacto</fo:inline></fo:block></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><fo:inline><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:Receptor/siidte:Contacto | /DTE/Documento/Encabezado/Receptor/Contacto"/></fo:inline></fo:block></fo:table-cell>
                                    </xsl:otherwise></xsl:choose>
                                    <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Ciudad</fo:inline></fo:block></fo:table-cell>
                                    <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="siidte:CiudadRecep | CiudadRecep"/></fo:block></fo:table-cell>
                                </fo:table-row>
                                <fo:table-row>
                                    <xsl:choose><xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=61) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=61) or (/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=56) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=56)">
                                        <fo:table-cell><fo:block margin-top="4pt"/></fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"/></fo:table-cell>
                                    </xsl:when><xsl:otherwise>
                                        <fo:table-cell>
                                            <fo:block margin-top="4pt"><fo:inline font-weight="bold">Cond. pago</fo:inline></fo:block>
                                        </fo:table-cell>
                                        <fo:table-cell><fo:block margin-top="4pt"><xsl:call-template name="PagoFormat">
                                            <xsl:with-param name="medioPago"><xsl:value-of select="$medioPago"/></xsl:with-param>
                                            <xsl:with-param name="formaPago"><xsl:value-of select="$formaPago"/></xsl:with-param>
                                        </xsl:call-template></fo:block></fo:table-cell>
                                    </xsl:otherwise></xsl:choose>
                                    <xsl:choose>
                                        <xsl:when test="(/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE=52) or (/DTE/Documento/Encabezado/IdDoc/TipoDTE=52)">
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Contacto</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:Receptor/siidte:Contacto | /DTE/Documento/Encabezado/Receptor/Contacto"/></fo:inline></fo:block></fo:table-cell>
                                        </xsl:when>
                                        <xsl:when test="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:FchVenc | /DTE/Documento/Encabezado/IdDoc/FchVenc">
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Vencimiento</fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline><xsl:call-template name="FechaFormat"><xsl:with-param name="fecha"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:FchVenc | /DTE/Documento/Encabezado/IdDoc/FchVenc"/></xsl:with-param></xsl:call-template></fo:inline></fo:block></fo:table-cell>
                                        </xsl:when>
                                        <xsl:otherwise>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold"></fo:inline></fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline></fo:inline></fo:block></fo:table-cell>
                                        </xsl:otherwise>
                                    </xsl:choose>
                                </fo:table-row>
                            </xsl:otherwise>
                        </xsl:choose>
                    </fo:table-body>
                </fo:table>
            </fo:block>

        </fo:block-container>
    </xsl:template>

    <!-- Detalle -->

    <xsl:template name="ItemColumnsAndHeader">
        <xsl:choose><xsl:when test="$showItemNumber = 'Y'">
            <fo:table-column column-width="0.5cm"/>
            <fo:table-column column-width="10.17cm"/>
        </xsl:when><xsl:otherwise>
            <fo:table-column column-width="10.67cm"/>
        </xsl:otherwise></xsl:choose>
        <fo:table-column column-width="1.5cm"/>
        <fo:table-column column-width="1.85cm"/>
        <fo:table-column column-width="1.6cm"/>
        <fo:table-column column-width="1.68cm"/>
        <!--
        <fo:table-column column-width="1.5cm"/>
        -->
        <fo:table-column column-width="2.5cm"/>
        <fo:table-header font-weight="bold">
            <xsl:attribute name="background-color"><xsl:value-of select="$detailHeaderBgColor"/></xsl:attribute>
            <xsl:attribute name="color"><xsl:value-of select="$detailHeaderFgColor"/></xsl:attribute>
            <xsl:if test="$showItemNumber = 'Y'">
                <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                    <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                    <fo:block margin="2pt">#</fo:block></fo:table-cell>
            </xsl:if>
            <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                <fo:block margin="2pt">Glosa</fo:block></fo:table-cell>
            <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                <fo:block margin="2pt">Cantidad</fo:block></fo:table-cell>
            <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                <fo:block margin="2pt">Precio Unit.</fo:block></fo:table-cell>
            <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                <fo:block margin="2pt">Dcto/Rcrg</fo:block></fo:table-cell>
            <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                <fo:block margin="2pt">Afecto IVA</fo:block></fo:table-cell>
            <!--
            <fo:table-cell border-right-width=".8pt" border-right-style="solid" text-align="center">
                <xsl:attribute name="border-right-color"><xsl:value-of select="$detailHeaderSepColor"/></xsl:attribute>
                <fo:block margin="2pt">Imp. Esp.</fo:block></fo:table-cell>
            -->
            <fo:table-cell text-align="center"><fo:block margin="2pt">Monto</fo:block></fo:table-cell>
        </fo:table-header>
    </xsl:template>

    <xsl:template match="siidte:Detalle | Detalle">
        <fo:table-row border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea">
            <xsl:if test="$showItemNumber = 'Y'">
                <fo:table-cell text-align="right">
                    <fo:block margin="2pt"><xsl:value-of select="siidte:NroLinDet | NroLinDet"/></fo:block>
                </fo:table-cell>
            </xsl:if>
            <fo:table-cell text-align="left">
                <fo:block margin="2pt">
                    <xsl:if test="siidte:VlrCodigo | VlrCodigo"><xsl:value-of select="siidte:VlrCodigo | VlrCodigo"/> -- </xsl:if>
                    <xsl:value-of select="siidte:NmbItem | NmbItem"/></fo:block>
                <xsl:if test="siidte:DscItem | DscItem"><fo:block margin="2pt"><xsl:value-of select="siidte:DscItem | DscItem"/></fo:block></xsl:if>
            </fo:table-cell>
            <fo:table-cell text-align="right">
                <xsl:choose>
                    <xsl:when test="number(siidte:QtyItem | QtyItem)">
                        <fo:block margin="2pt"><xsl:value-of select="format-number(siidte:QtyItem | QtyItem, '###.##0,###', 'european')"/>&#160;<xsl:value-of select="siidte:UnmdItem | UnmdItem"/></fo:block>
                    </xsl:when>
                    <xsl:otherwise>
                        <fo:block margin="2pt">-</fo:block>
                    </xsl:otherwise>
                </xsl:choose>
            </fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                <xsl:choose>
                    <xsl:when test="siidte:PrcItem | PrcItem | siidte:DescuentoPct | DescuentoPct">$&#160;<xsl:value-of select="format-number(siidte:PrcItem | PrcItem, '###.###', 'european')"/></xsl:when>
                    <xsl:otherwise>0</xsl:otherwise>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                <xsl:choose>
                    <xsl:when test="siidte:DescuentoPct | DescuentoPct"><xsl:value-of select="format-number(siidte:DescuentoPct | DescuentoPct, '#0,###', 'european')"/>%</xsl:when>
                    <xsl:when test="siidte:DescuentoMonto | DescuentoMonto">$&#160;<xsl:value-of select="format-number(siidte:DescuentoMonto | DescuentoMonto, '###.###', 'european')"/></xsl:when>
                    <xsl:otherwise> </xsl:otherwise>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell>
                <fo:block margin="2pt">
                    <xsl:choose>
                        <xsl:when test="siidte:IndExe | IndExe">NO</xsl:when>
                        <xsl:otherwise>SI</xsl:otherwise>
                    </xsl:choose>
                </fo:block>
            </fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                $&#160;<xsl:value-of select="format-number(siidte:MontoItem | MontoItem, '###.###', 'european')"/></fo:block></fo:table-cell>
        </fo:table-row>
    </xsl:template>

    <!-- Referencias -->
    <xsl:template name="ReferencesColumnsAndHeader">
        <fo:table-column column-width="2.84cm"/>
        <fo:table-column column-width="2.54cm"/>
        <fo:table-column column-width="1.94cm"/>
        <fo:table-column column-width="4.04cm"/>
        <fo:table-column column-width="2.84cm"/>
        <fo:table-header><fo:table-row>
            <fo:table-cell><fo:block font-weight="bold">Documento ref.</fo:block></fo:table-cell>
            <fo:table-cell><fo:block font-weight="bold">Folio</fo:block></fo:table-cell>
            <fo:table-cell><fo:block font-weight="bold">Fecha</fo:block></fo:table-cell>
            <fo:table-cell><fo:block font-weight="bold">Razón ref.</fo:block></fo:table-cell>
            <fo:table-cell><fo:block font-weight="bold">Tipo de oper.</fo:block></fo:table-cell>
        </fo:table-row></fo:table-header>
    </xsl:template>

    <xsl:template match="siidte:Referencia | Referencia">
        <fo:table-row>
            <fo:table-cell text-align="left"><fo:block>
                <xsl:choose>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=30">Factura</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=32">Factura de Venta Bienes y Servicios</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=33">Factura Electrónica</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=34">Factura Electrónica Exenta</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=35">Boleta</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=38">Boleta Exenta</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=39">Boleta Electrónica</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=52">Guía de Despacho Electrónica</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=56">Nota de Débito Electrónica</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=61">Nota de Crédito Electrónica</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=801">Orden de Compra</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=802">Nota de pedido</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=803">Contrato</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=804">Resolución</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=805">Proceso ChileCompra</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=806">Ficha ChileCompra</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=807">DUS</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=808">B/L (Conocimiento de embarque)</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=809">AWB (Air Will Bill)</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=810">MIC/DTA</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=811">Carta de Porte</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=812">Resolución del SNA donde califica Servicios de Exportación</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=813">Pasporte</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=814">Certificado de Depósito Bolsa Prod. Chile</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=815">Vale de Prenda Bolsa Prod. Chile</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=820">Código de Inscripción en el Registro de Acuerdos con Plazo de Pago Excepcional</xsl:when>
                    <xsl:when test="(siidte:TpoDocRef | TpoDocRef)=HES">Hoja de Entrada en Servicio (HES)</xsl:when>
                    <xsl:otherwise>Tipo <xsl:value-of select="siidte:TpoDocRef | TpoDocRef"/></xsl:otherwise>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell><fo:block><xsl:value-of select="siidte:FolioRef | FolioRef"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block><xsl:value-of select="siidte:FchRef | FchRef"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block><xsl:value-of select="siidte:RazonRef | RazonRef"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block>
                <xsl:choose>
                    <xsl:when test="(siidte:CodRef | CodRef)=1">Anula Doc. Ref.</xsl:when>
                    <xsl:when test="(siidte:CodRef | CodRef)=2">Corrige Texto</xsl:when>
                    <xsl:when test="(siidte:CodRef | CodRef)=3">Corrige Montos</xsl:when>
                </xsl:choose>
            </fo:block></fo:table-cell>
        </fo:table-row>
    </xsl:template>

    <xsl:template name="DscRcgGlobalTable">
        <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                  fox:border-radius="4pt" border-width="0.8pt" border-style="solid">
            <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
            <fo:table table-layout="fixed" margin="2pt">
                <fo:table-column column-width="0.5cm"/>
                <fo:table-column column-width="2.9cm"/>
                <fo:table-column column-width="13.4cm"/>
                <fo:table-column column-width="3cm"/>
                <fo:table-body>
                    <xsl:apply-templates select="siidte:DscRcgGlobal | DscRcgGlobal"/>
                </fo:table-body>
            </fo:table>
        </fo:block>
    </xsl:template>

    <xsl:template match="siidte:DscRcgGlobal | DscRcgGlobal">
        <fo:table-row>
            <fo:table-cell><fo:block margin="2pt"><xsl:value-of select="siidte:NroLinDR | NroLinDR"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block margin="2pt" font-weight="bold">
                <xsl:choose>
                    <xsl:when test="(siidte:TpoMov | TpoMov) = 'D'">Descuento Global</xsl:when>
                    <xsl:when test="(siidte:TpoMov | TpoMov) = 'R'">Recargo Global</xsl:when>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell><fo:block margin="2pt"><xsl:value-of select="siidte:GlosaDR | GlosaDR"/></fo:block></fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                <xsl:if test="(siidte:TpoMov | TpoMov) = 'D'">(</xsl:if>
                <xsl:if test="(siidte:TpoValor | TpoValor) = '$'">$&#160;</xsl:if>
                <xsl:value-of select="format-number(siidte:ValorDR | ValorDR, '###.###','european')"/>
                <xsl:if test="(siidte:TpoValor | TpoValor) = '%'">&#160;%</xsl:if>
                <xsl:if test="(siidte:TpoMov | TpoMov) = 'D'">)</xsl:if>
            </fo:block></fo:table-cell>
        </fo:table-row>
    </xsl:template>

    <!-- Timbre electrónico -->
    <xsl:template match="siidte:TED | TED">
        <xsl:variable name="myted" select="."/>
        <fo:block-container margin-top="4pt" margin-left="1pt">
            <fo:block><fo:instream-foreign-object content-width="5.6cm"><xsl:copy-of select="tedbarcode:generate($myted)"/></fo:instream-foreign-object></fo:block>
            <fo:block font-size="7pt" font-family="sans-serif" text-align="center">Timbre Electrónico SII</fo:block>
            <fo:block font-size="7pt" font-family="sans-serif" text-align="center">Resolución Ex. SII N° <xsl:value-of select="$numeroResolucionSii"/> de <xsl:value-of select="$fechaResolucionSii"/> - Verifique Documento: www.sii.cl</fo:block>
        </fo:block-container>
    </xsl:template>

    <xsl:template name="PagoFormat">
        <xsl:param name="medioPago"/>
        <xsl:param name="formaPago"/>

        <xsl:choose>
            <xsl:when test="$medioPago='CH'">Cheque</xsl:when>
            <xsl:when test="$medioPago='LT'">Letra</xsl:when>
            <xsl:when test="$medioPago='EF'">Efectivo</xsl:when>
            <xsl:when test="$medioPago='PE'">Pago a Cta. Corriente</xsl:when>
            <xsl:when test="$medioPago='TC'">Tarjeta de Crédito</xsl:when>
            <xsl:when test="$medioPago='CF'">Cheque a Fecha</xsl:when>
            <xsl:when test="$medioPago='OT'">Otro</xsl:when>
        </xsl:choose>

        <xsl:choose>
            <xsl:when test="$formaPago=1"> (Contado)</xsl:when>
            <xsl:when test="$formaPago=2"> <xsl:if test="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TermPagoGlosa | /DTE/Documento/Encabezado/IdDoc/TermPagoGlosa"> (<xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TermPagoGlosa | /DTE/Documento/Encabezado/IdDoc/TermPagoGlosa"/>)</xsl:if></xsl:when>
            <xsl:when test="$formaPago=3"> (Sin Valor)</xsl:when>
        </xsl:choose>


    </xsl:template>

    <xsl:template name="FechaFormat">
        <xsl:param name="fecha"/>
        <xsl:value-of select="substring($fecha,string-length($fecha)-1,2)"/> de <xsl:choose>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=01"> Enero </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=02"> Febrero </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=03"> Marzo </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=04"> Abril </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=05"> Mayo </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=06"> Junio </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=07"> Julio </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=08"> Agosto </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=09"> Septiembre </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=10"> Octubre </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=11"> Noviembre </xsl:when>
        <xsl:when test="substring($fecha,string-length($fecha)-4,2)=12"> Diciembre </xsl:when>
    </xsl:choose>
        de
        <xsl:value-of select="substring($fecha,string-length($fecha)-9,4)"/>
    </xsl:template>

    <xsl:template name="RutFormat">
        <xsl:param name="rut"/>
        <xsl:variable name="num" select="substring-before($rut,'-')"/>
        <xsl:variable name="dv" select="substring-after($rut,'-')"/>

        <xsl:value-of select="substring($num,string-length($num)-8,3)"/>.<xsl:value-of
            select="substring($num,string-length($num)-5,3)"/>.<xsl:value-of
            select="substring($num,string-length($num)-2,3)"/>-<xsl:value-of select="$dv"/>

    </xsl:template>

    <xsl:template name="ExceedingItemsAndRefs">
        <xsl:variable name="folio"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:Folio | /DTE/Documento/Encabezado/IdDoc/Folio"/></xsl:variable>
        <xsl:variable name="tipo"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:IdDoc/siidte:TipoDTE | /DTE/Documento/Encabezado/IdDoc/TipoDTE"/></xsl:variable>
        <fo:block>
            <fo:block-container top="0cm" left="0cm">
                <fo:block font-size="12pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" space-after="8pt" language="es" hyphenate="true" color="black" text-align="left" margin-right="2pt">
                    Adjunto a <xsl:choose>
                    <xsl:when test="$tipo=33">Factura Electrónica</xsl:when>
                    <xsl:when test="$tipo=34">Factura Electrónica Exenta</xsl:when>
                    <xsl:when test="$tipo=52">Guía de Despacho Electrónica</xsl:when>
                    <xsl:when test="$tipo=56">Nota de Débito Electrónica</xsl:when>
                    <xsl:when test="$tipo=61">Nota de Crédito Electrónica</xsl:when>
                    <xsl:when test="$tipo=110">Factura de Exportación Electrónica</xsl:when>
                    <xsl:when test="$tipo=112">Nota de Crédito de Exportación Electrónica</xsl:when>
                    <xsl:otherwise>Tipo de Documento Desconocido</xsl:otherwise>
                </xsl:choose> folio <xsl:value-of select="$folio"/> Rut <xsl:call-template name="RutFormat"><xsl:with-param name="rut"><xsl:value-of select="/siidte:DTE/siidte:Documento/siidte:Encabezado/siidte:Emisor/siidte:RUTEmisor | /DTE/Documento/Encabezado/Emisor/RUTEmisor"/></xsl:with-param></xsl:call-template>
                </fo:block>
                <xsl:if test="count(/siidte:DTE/siidte:Documento/siidte:Detalle | /DTE/Documento/Detalle) > $maxItems and $showExcessItemsInOtherPage = 'Y'">
                    <fo:block font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left" margin-right="2pt">
                        Ítemes <xsl:value-of select="$maxItems + 1"/> a <xsl:value-of select="count(/siidte:DTE/siidte:Documento/siidte:Detalle | /DTE/Documento/Detalle)"/>
                    </fo:block>
                    <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="8pt" language="es" hyphenate="true" color="black" text-align="left"
                              fox:border-radius="4pt" border-width="0.8pt"  border-style="solid">
                        <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                        <fo:block-container>
                            <fo:table table-layout="fixed" width="100%" border-collapse="collapse">
                                <xsl:call-template name="ItemColumnsAndHeader"/>
                                <fo:table-body>
                                    <xsl:apply-templates select="/siidte:DTE/siidte:Documento/siidte:Detalle[position() > $maxItems] | /DTE/Documento/Detalle[position() > $maxItems]"/>
                                </fo:table-body>
                            </fo:table>
                        </fo:block-container>
                    </fo:block>
                </xsl:if>
                <xsl:if test="count(/siidte:DTE/siidte:Documento/siidte:Referencia | /DTE/Documento/Referencia) > $maxRefs and $showExcessRefsInOtherPage = 'Y'">
                    <fo:block font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left" margin-right="2pt">
                        Referencias <xsl:value-of select="$maxRefs + 1"/> a <xsl:value-of select="count(/siidte:DTE/siidte:Documento/siidte:Referencia | /DTE/Documento/Referencia)"/>
                    </fo:block>
                    <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                              fox:border-radius="4pt" border-width="0.8pt" border-style="solid" margin-right="2pt">
                        <xsl:attribute name="border-color"><xsl:value-of select="$tableBorderColor"/></xsl:attribute>
                        <fo:table table-layout="fixed" margin="2pt">
                            <xsl:call-template name="ReferencesColumnsAndHeader"/>
                            <fo:table-body>
                                <xsl:apply-templates select="/siidte:DTE/siidte:Documento/siidte:Referencia[position() > $maxRefs] | /DTE/Documento/Referencia[position() > $maxRefs]"/>
                            </fo:table-body>
                        </fo:table>
                    </fo:block>
                </xsl:if>
            </fo:block-container>
        </fo:block>
    </xsl:template>

    <xsl:template name="CodigoPuertoDetail">
        <xsl:param name="codigoPuerto"/>
        <xsl:choose>
            <xsl:when test="$codigoPuerto=111">Montreal, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=112">Otros Costa del Pacífico, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=113">Halifax, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=114">Vancouver, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=115">Saint John, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=116">Toronto, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=117">Otros Puertos de Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=118">Bayside, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=120">Port Carties, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=121">Costa del Atlántico, Otros entre Maine y Key West, USA</xsl:when>
            <xsl:when test="$codigoPuerto=122">Golfo de México, Otros entre Key West y Brownsville, USA</xsl:when>
            <xsl:when test="$codigoPuerto=123">Otros Costa del Pacífico, USA</xsl:when>
            <xsl:when test="$codigoPuerto=124">Quebec, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=125">Prince Rupert, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=126">Halmilton, Canadá</xsl:when>
            <xsl:when test="$codigoPuerto=131">Boston, USA</xsl:when>
            <xsl:when test="$codigoPuerto=132">New Haven, USA</xsl:when>
            <xsl:when test="$codigoPuerto=133">Bridgeport, USA</xsl:when>
            <xsl:when test="$codigoPuerto=134">New York, USA</xsl:when>
            <xsl:when test="$codigoPuerto=135">Filadelfia, USA</xsl:when>
            <xsl:when test="$codigoPuerto=136">Baltimore, USA</xsl:when>
            <xsl:when test="$codigoPuerto=137">Norfolk, USA</xsl:when>
            <xsl:when test="$codigoPuerto=139">Charleston, USA</xsl:when>
            <xsl:when test="$codigoPuerto=140">Savanah, USA</xsl:when>
            <xsl:when test="$codigoPuerto=141">Miami, USA</xsl:when>
            <xsl:when test="$codigoPuerto=142">Everglades, USA</xsl:when>
            <xsl:when test="$codigoPuerto=143">Jacksonville, USA</xsl:when>
            <xsl:when test="$codigoPuerto=145">Palm Beach, USA</xsl:when>
            <xsl:when test="$codigoPuerto=146">Baton Rouge, USA</xsl:when>
            <xsl:when test="$codigoPuerto=147">Columbres, USA</xsl:when>
            <xsl:when test="$codigoPuerto=148">Pittsburgh, USA</xsl:when>
            <xsl:when test="$codigoPuerto=149">Duluth, USA</xsl:when>
            <xsl:when test="$codigoPuerto=150">Milwaukee, USA</xsl:when>
            <xsl:when test="$codigoPuerto=151">Tampa, USA</xsl:when>
            <xsl:when test="$codigoPuerto=152">Pensacola, USA</xsl:when>
            <xsl:when test="$codigoPuerto=153">Mobile, USA</xsl:when>
            <xsl:when test="$codigoPuerto=154">New Orleans, USA</xsl:when>
            <xsl:when test="$codigoPuerto=155">Port Arthur, USA</xsl:when>
            <xsl:when test="$codigoPuerto=156">Galvestón, USA</xsl:when>
            <xsl:when test="$codigoPuerto=157">Corpus Cristi, USA</xsl:when>
            <xsl:when test="$codigoPuerto=158">Brownsville, USA</xsl:when>
            <xsl:when test="$codigoPuerto=159">Houston, USA</xsl:when>
            <xsl:when test="$codigoPuerto=160">Oakland, USA</xsl:when>
            <xsl:when test="$codigoPuerto=161">Stockton, USA</xsl:when>
            <xsl:when test="$codigoPuerto=171">Seattle, USA</xsl:when>
            <xsl:when test="$codigoPuerto=172">Portland, USA</xsl:when>
            <xsl:when test="$codigoPuerto=173">San Francisco, USA</xsl:when>
            <xsl:when test="$codigoPuerto=174">Los Angeles, USA</xsl:when>
            <xsl:when test="$codigoPuerto=175">Long Beach, USA</xsl:when>
            <xsl:when test="$codigoPuerto=176">San Diego, USA</xsl:when>
            <xsl:when test="$codigoPuerto=180">Otros Puertos No Especificados, USA</xsl:when>
            <xsl:when test="$codigoPuerto=199">Los Vilos, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=201">Puchoco, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=202">Oxiquim, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=204">Patache, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=205">Calbuco, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=206">Michilla, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=207">Puerto Angamos, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=208">Poseidón, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=209">Tres Puentes, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=210">Otros Puertos No Especificados, México</xsl:when>
            <xsl:when test="$codigoPuerto=211">Tampico, México</xsl:when>
            <xsl:when test="$codigoPuerto=212">Otros Costa del Pacífico, México</xsl:when>
            <xsl:when test="$codigoPuerto=213">Veracruz, México</xsl:when>
            <xsl:when test="$codigoPuerto=214">Coatzacoalcos, México</xsl:when>
            <xsl:when test="$codigoPuerto=215">Guaymas, México</xsl:when>
            <xsl:when test="$codigoPuerto=216">Mazatlán, México</xsl:when>
            <xsl:when test="$codigoPuerto=217">Manzanillo, México</xsl:when>
            <xsl:when test="$codigoPuerto=218">Acapulco, México</xsl:when>
            <xsl:when test="$codigoPuerto=219">Otros Golfo de México, México</xsl:when>
            <xsl:when test="$codigoPuerto=220">Altamira, México</xsl:when>
            <xsl:when test="$codigoPuerto=221">Cristobal, Panamá</xsl:when>
            <xsl:when test="$codigoPuerto=222">Balboa, Panamá</xsl:when>
            <xsl:when test="$codigoPuerto=223">Colón, Panamá</xsl:when>
            <xsl:when test="$codigoPuerto=224">Otros Puertos No Especificados, Panamá</xsl:when>
            <xsl:when test="$codigoPuerto=231">Otros Puertos No Especificados, Colombia</xsl:when>
            <xsl:when test="$codigoPuerto=232">Buenaventura, Colombia</xsl:when>
            <xsl:when test="$codigoPuerto=233">Barranquilla, Colombia</xsl:when>
            <xsl:when test="$codigoPuerto=241">Otros Puertos No Especificados, Ecuador</xsl:when>
            <xsl:when test="$codigoPuerto=242">Guayaquil, Ecuador</xsl:when>
            <xsl:when test="$codigoPuerto=251">Otros Puertos No Especificados, Perú</xsl:when>
            <xsl:when test="$codigoPuerto=252">Callao, Perú</xsl:when>
            <xsl:when test="$codigoPuerto=253">Ilo, Perú</xsl:when>
            <xsl:when test="$codigoPuerto=254">Iquitos, Perú</xsl:when>
            <xsl:when test="$codigoPuerto=261">Otros Puertos No Especificados, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=262">Buenos Aires, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=263">Necochea, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=264">Mendoza, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=265">Cordoba, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=266">Bahía Blanca, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=267">Comodoro Rivadavia, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=268">Puerto Madryn, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=269">Mar del Plata, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=270">Rosario, Argentina</xsl:when>
            <xsl:when test="$codigoPuerto=271">Otros Puertos No Especificados, Uruguay</xsl:when>
            <xsl:when test="$codigoPuerto=272">Montevideo, Uruguay</xsl:when>
            <xsl:when test="$codigoPuerto=281">Otros Puertos No Especificados, Venezuela</xsl:when>
            <xsl:when test="$codigoPuerto=282">La Guaira, Venezuela</xsl:when>
            <xsl:when test="$codigoPuerto=285">Maracaibo, Venezuela</xsl:when>
            <xsl:when test="$codigoPuerto=291">Otros Puertos No Especificados, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=292">Santos, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=293">Río de Janeiro, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=294">Río Grande del Sur, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=295">Paranaguá, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=296">Sao Paulo, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=297">Salvador, Brasil</xsl:when>
            <xsl:when test="$codigoPuerto=301">Otros Puertos No Especificados, Antillas Holandesas</xsl:when>
            <xsl:when test="$codigoPuerto=302">Curazao, Antillas Holandesas</xsl:when>
            <xsl:when test="$codigoPuerto=399">Otros Puertos de América No Especificados, Antillas Holandesas</xsl:when>
            <xsl:when test="$codigoPuerto=411">Shangai, China</xsl:when>
            <xsl:when test="$codigoPuerto=412">Dairén, China</xsl:when>
            <xsl:when test="$codigoPuerto=413">Otros Puertos No Especificados, China</xsl:when>
            <xsl:when test="$codigoPuerto=420">Otros Puertos No Especificados, Corea del Norte</xsl:when>
            <xsl:when test="$codigoPuerto=421">Nampo, Corea del Norte</xsl:when>
            <xsl:when test="$codigoPuerto=422">Busan CY (Pusan), Corea del Norte</xsl:when>
            <xsl:when test="$codigoPuerto=423">Otros Puertos No Especificados, Corea del Sur</xsl:when>
            <xsl:when test="$codigoPuerto=431">Manila, Corea del Sur</xsl:when>
            <xsl:when test="$codigoPuerto=432">Otros Puertos No Especificados, Filipinas</xsl:when>
            <xsl:when test="$codigoPuerto=441">Otros Puertos No Especificados, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=442">Osaka, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=443">Kobe, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=444">Yokohama, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=445">Nagoya, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=446">Shimizui, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=447">Moji, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=448">Yawata, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=449">Fukuyama, Japón</xsl:when>
            <xsl:when test="$codigoPuerto=451">Kaohsiung, Taiwán</xsl:when>
            <xsl:when test="$codigoPuerto=452">Keelung, Taiwán</xsl:when>
            <xsl:when test="$codigoPuerto=453">Otros Puertos No Especificados, Taiwán</xsl:when>
            <xsl:when test="$codigoPuerto=461">Karhg Island, Irán</xsl:when>
            <xsl:when test="$codigoPuerto=462">Otros Puertos No Especificados, Irán</xsl:when>
            <xsl:when test="$codigoPuerto=471">Calculta, India</xsl:when>
            <xsl:when test="$codigoPuerto=472">Otros Puertos No Especificados, India</xsl:when>
            <xsl:when test="$codigoPuerto=481">Chalna, Bangladesh</xsl:when>
            <xsl:when test="$codigoPuerto=482">Otros Puertos No Especificados, Bangladesh</xsl:when>
            <xsl:when test="$codigoPuerto=491">Otros Puertos No Especificados, Singapur</xsl:when>
            <xsl:when test="$codigoPuerto=492">Hong Kong, Singapur</xsl:when>
            <xsl:when test="$codigoPuerto=499">Otros Puertos Asiáticos No Especificados, Singapur</xsl:when>
            <xsl:when test="$codigoPuerto=511">Constanza, Rumania</xsl:when>
            <xsl:when test="$codigoPuerto=512">Otros Puertos No Especificados, Rumania</xsl:when>
            <xsl:when test="$codigoPuerto=521">Varna, Bulgaria</xsl:when>
            <xsl:when test="$codigoPuerto=522">Otros Puertos No Especificados, Bulgaria</xsl:when>
            <xsl:when test="$codigoPuerto=531">Rijeka</xsl:when>
            <xsl:when test="$codigoPuerto=532">Otros Puertos No Especificados, Yugoeslavia</xsl:when>
            <xsl:when test="$codigoPuerto=533">Belgrado, República de Serbia</xsl:when>
            <xsl:when test="$codigoPuerto=534">Otros Puertos No Especificados, República de Serbia</xsl:when>
            <xsl:when test="$codigoPuerto=535">Podgoritsa, Montenegro</xsl:when>
            <xsl:when test="$codigoPuerto=536">Otros Puertos No Especificados, Montenegro</xsl:when>
            <xsl:when test="$codigoPuerto=537">Otros Puertos No Especificados, Croacia</xsl:when>
            <xsl:when test="$codigoPuerto=538">Rijeka, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=541">Otros Puertos No Especificados, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=542">Genova, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=543">Liorna, Livorno, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=544">Napoles, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=545">Salerno, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=546">Augusta, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=547">Savona, Italia</xsl:when>
            <xsl:when test="$codigoPuerto=551">Otros Puertos No Especificados, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=552">La Pallice, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=553">Le Havre, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=554">Marsella, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=555">Burdeos, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=556">Calais, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=557">Brest, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=558">Ruan, Francia</xsl:when>
            <xsl:when test="$codigoPuerto=561">Otros Puertos No Especificados, España</xsl:when>
            <xsl:when test="$codigoPuerto=562">Cadiz, España</xsl:when>
            <xsl:when test="$codigoPuerto=563">Barcelona, España</xsl:when>
            <xsl:when test="$codigoPuerto=564">Bilbao, España</xsl:when>
            <xsl:when test="$codigoPuerto=565">Huelva, España</xsl:when>
            <xsl:when test="$codigoPuerto=566">Sevilla, España</xsl:when>
            <xsl:when test="$codigoPuerto=567">Tarragona, España</xsl:when>
            <xsl:when test="$codigoPuerto=568">Algeciras, España</xsl:when>
            <xsl:when test="$codigoPuerto=569">Valencia, España</xsl:when>
            <xsl:when test="$codigoPuerto=571">Liverpool, Inglaterra</xsl:when>
            <xsl:when test="$codigoPuerto=572">Londres, Inglaterra</xsl:when>
            <xsl:when test="$codigoPuerto=573">Rochester, Inglaterra</xsl:when>
            <xsl:when test="$codigoPuerto=574">Eten Salverry, Inglaterra</xsl:when>
            <xsl:when test="$codigoPuerto=576">Otros Puertos No Especificados, Inglaterra</xsl:when>
            <xsl:when test="$codigoPuerto=577">Dover, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=578">Plymouth, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=581">Helsinki, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=582">Otros Puertos No Especificados, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=583">Hanko, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=584">Kemi, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=585">Kokkola, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=586">Kotka, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=587">Oulo, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=588">Pietarsaari, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=589">Pori, Finlandia</xsl:when>
            <xsl:when test="$codigoPuerto=591">Bremen, Alemania</xsl:when>
            <xsl:when test="$codigoPuerto=592">Hamburgo, Alemania</xsl:when>
            <xsl:when test="$codigoPuerto=593">Nuremberg, Alemania</xsl:when>
            <xsl:when test="$codigoPuerto=594">Frankfurt, Alemania</xsl:when>
            <xsl:when test="$codigoPuerto=595">Dusseldorf, Alemania</xsl:when>
            <xsl:when test="$codigoPuerto=596">Otros Puertos No Especificados, Alemania</xsl:when>
            <xsl:when test="$codigoPuerto=597">Cuxhaven, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=598">Rostock, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=599">Oldenburg, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=601">Amberes, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=602">Otros Puertos No Especificados, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=603">Zeebrugge, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=604">Ghent, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=605">Oostende, Bélgica</xsl:when>
            <xsl:when test="$codigoPuerto=611">Lisboa, Portugal</xsl:when>
            <xsl:when test="$codigoPuerto=612">Otros Puertos No Especificados, Portugal</xsl:when>
            <xsl:when test="$codigoPuerto=613">Setubal, Portugal</xsl:when>
            <xsl:when test="$codigoPuerto=621">Amsterdam, Holanda</xsl:when>
            <xsl:when test="$codigoPuerto=622">Rotterdam, Holanda</xsl:when>
            <xsl:when test="$codigoPuerto=623">Otros Puertos No Especificados, Holanda</xsl:when>
            <xsl:when test="$codigoPuerto=631">Gotemburgo</xsl:when>
            <xsl:when test="$codigoPuerto=632">Otros Puertos No Especificados, Suecia</xsl:when>
            <xsl:when test="$codigoPuerto=633">Malmo, Suecia</xsl:when>
            <xsl:when test="$codigoPuerto=634">Helsimborg, Suecia</xsl:when>
            <xsl:when test="$codigoPuerto=635">Kalmar, Suecia</xsl:when>
            <xsl:when test="$codigoPuerto=641">Aarhus, Dinamarca</xsl:when>
            <xsl:when test="$codigoPuerto=642">Copenhagen, Dinamarca</xsl:when>
            <xsl:when test="$codigoPuerto=643">Otros Puertos No Especificados, Dinamarca</xsl:when>
            <xsl:when test="$codigoPuerto=644">Aalborg, Dinamarca</xsl:when>
            <xsl:when test="$codigoPuerto=645">Odense, Dinamarca</xsl:when>
            <xsl:when test="$codigoPuerto=651">Oslo, Noruega</xsl:when>
            <xsl:when test="$codigoPuerto=652">Otros Puertos No Especificados, Noruega</xsl:when>
            <xsl:when test="$codigoPuerto=653">Stavanger, Noruega</xsl:when>
            <xsl:when test="$codigoPuerto=699">Otros Puertos de Europa No Especificados</xsl:when>
            <xsl:when test="$codigoPuerto=711">Durbam, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=712">Ciudad del Cabo, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=713">Otros Puertos No Especificados, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=714">Saldanha, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=715">Port-Elizabeth, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=716">Mossel-Bay, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=717">East-London, Sudáfrica</xsl:when>
            <xsl:when test="$codigoPuerto=799">Otros Puertos No Especificados, África</xsl:when>
            <xsl:when test="$codigoPuerto=811">Sidney, Australia</xsl:when>
            <xsl:when test="$codigoPuerto=812">Fremantle, Australia</xsl:when>
            <xsl:when test="$codigoPuerto=813">Otros Puertos No Especificados, Australia</xsl:when>
            <xsl:when test="$codigoPuerto=814">Adelaida, Australia</xsl:when>
            <xsl:when test="$codigoPuerto=815">Darwin, Australia</xsl:when>
            <xsl:when test="$codigoPuerto=816">Geraldton, Australia</xsl:when>
            <xsl:when test="$codigoPuerto=899">Otros Puertos No Especificados, Oceanía</xsl:when>
            <xsl:when test="$codigoPuerto=900">Rancho de Naves y Aeronaves de Transporte Internacional</xsl:when>
            <xsl:when test="$codigoPuerto=901">Arica, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=902">Iquique, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=903">Antofagasta, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=904">Coquimbo, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=905">Valparaíso, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=906">San Antonio, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=907">Talcahuano, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=908">San Vicente, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=909">Lirquén, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=910">Puerto Montt, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=911">Chacabuco / Puerto Aysén, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=912">Punta Arenas, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=913">Patillos, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=914">Tocopilla, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=915">Mejillones, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=916">Taltal, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=917">Chañaral / Barquito, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=918">Caldera, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=919">Calderilla, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=920">Huasco / Guacolda, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=921">Quintero, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=922">Juan Fernandez, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=923">Constitución, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=924">Tomé, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=925">Penco, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=926">Coronel, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=927">Lota, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=928">Lebu, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=929">Isla de Pascua, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=930">Corral, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=931">Ancud, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=932">Castro, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=933">Quellón, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=934">Chaitén, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=935">Tortel, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=936">Natales, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=937">Guarello, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=938">Puerto Andino, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=939">Percy, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=940">Clarencia, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=941">Gregorio, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=942">Cabo Negro, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=943">Puerto Williams, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=944">Territorio Antártico Chileno, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=945">Aeropuerto Carriel Sur, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=946">Guayacán, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=947">Punta Delgada, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=948">Ventanas, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=949">Pino Hachado (Liucura), Chile</xsl:when>
            <xsl:when test="$codigoPuerto=950">Caleta Coloso, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=951">Aguas Negras, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=952">Zona Franca Iquique</xsl:when>
            <xsl:when test="$codigoPuerto=953">Zona Franca Punta Arenas</xsl:when>
            <xsl:when test="$codigoPuerto=954">Río Mayer, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=955">Río Mosco, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=956">Visviri, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=957">Chacalluta, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=958">Chungará, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=959">Colchane, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=960">Abra de Napa, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=961">Ollague, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=962">San Pedro de Atacama, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=963">Socompa, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=964">San Francisco, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=965">Los Libertadores, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=966">Mahuil Malal, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=967">Cardenal Samore, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=968">Perez Rosales, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=969">Futaleufu, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=970">Palena - Carrenleufu, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=971">Panguipulli, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=972">Huahum, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=973">Lago Verde, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=974">Appeleg, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=975">Pampa Alta, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=976">Huemules, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=977">Chile Chico, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=978">Baker, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=979">Dorotea, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=980">Casas Viejas, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=981">Monte Aymond, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=982">San Sebastián, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=983">Coyhaique Alto, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=984">Triana, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=985">Ibañez Palavicini, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=986">Villa O'Higgins, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=987">Aerop. Chacalluta, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=988">Aerop. Diego Aracena, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=989">Aerop. Cerro Moreno, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=990">Aerop. El Tepual, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=991">Aerop. C.I. del Campo, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=992">Aerop. A.M. Benitez, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=993">CAP. Huachipato, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=994">Estación de Ferrocarril Arica - Tacna, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=995">Estación de Ferrocarril Arica - La Paz, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=996">Terminal Petrolero ENAP, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=997">Otros Puertos Chilenos, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=998">Paso Jama, Chile</xsl:when>
            <xsl:when test="$codigoPuerto=''">-</xsl:when>
            <xsl:otherwise><xsl:value-of select="$codigoPuerto"/>(desconocido)</xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <xsl:template name="CodigoModalidadVenta">
        <xsl:param name="codigoModalidadVenta"/>
        <xsl:choose>
            <xsl:when test="$codigoModalidadVenta=1">A Firme</xsl:when>
            <xsl:when test="$codigoModalidadVenta=2">Bajo Condición</xsl:when>
            <xsl:when test="$codigoModalidadVenta=3">En Consignación Libre</xsl:when>
            <xsl:when test="$codigoModalidadVenta=4">En Consignación con un Mínimo a Firme</xsl:when>
            <xsl:when test="$codigoModalidadVenta=9">Sin Pago</xsl:when>
        </xsl:choose>
    </xsl:template>
</xsl:stylesheet>
