<?xml version="1.0" encoding="UTF-8"?>

<xsl:stylesheet version="1.1" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:fo="http://www.w3.org/1999/XSL/Format"
                xmlns:tedbarcode="cl.nic.dte.fop.TedBarcodeExtension" extension-element-prefixes="tedbarcode" xmlns:fox="http://xmlgraphics.apache.org/fop/extensions">

    <xsl:param name="fonoContacto"/>
    <xsl:param name="mailContacto"/>
    <xsl:param name="oficinaSII" select="'Santiago Oriente'"/>
    <xsl:param name="logo"/>
    <xsl:param name="maxItems"/>
    <xsl:param name="cedible"/>
    <xsl:param name="nroResol" select="'80'"/>
    <xsl:param name="fchResol" select="'2014'"/>
    <xsl:param name="tipoDocumento" select="Encabezado/IdDoc/TipoDTE"/>

    <xsl:output method="xml" version="1.0" omit-xml-declaration="no" indent="yes" encoding="UTF-8"/>

    <xsl:decimal-format name="us" decimal-separator='.' grouping-separator=','/>
    <xsl:decimal-format name="european" decimal-separator=',' grouping-separator='.'/>

    <xsl:param name="versionParam" select="'1.0'"/>
    <xsl:template match="/">
        <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
            <fo:layout-master-set>
                <fo:simple-page-master master-name="simple" page-height="27.9cm" page-width="21.6cm" margin-top="1cm" margin-bottom="2cm" margin-left="0.8cm" margin-right="1cm">
                    <fo:region-body margin-top="0cm"/>
                    <fo:region-before extent="3cm"/>
                    <fo:region-after extent="1.5cm"/>
                </fo:simple-page-master>
            </fo:layout-master-set>

            <fo:page-sequence master-reference="simple">
                <fo:flow flow-name="xsl-region-body"><xsl:apply-templates select="DTE/Documento"/></fo:flow>
            </fo:page-sequence>

        </fo:root>
    </xsl:template>

    <xsl:template match="DTE/Documento">
        <fo:block>
            <fo:block-container absolute-position="absolute" top="0cm" left="0cm">
                <xsl:apply-templates select="Encabezado/Emisor">
                    <xsl:with-param name="folio"><xsl:value-of select="Encabezado/IdDoc/Folio"/></xsl:with-param>
                    <xsl:with-param name="tipo"><xsl:value-of select="Encabezado/IdDoc/TipoDTE"/></xsl:with-param>
                </xsl:apply-templates>
                <xsl:apply-templates select="Encabezado/Receptor">
                    <xsl:with-param name="fecha"><xsl:value-of select="Encabezado/IdDoc/FchEmis"/></xsl:with-param>
                    <xsl:with-param name="medioPago"><xsl:value-of select="Encabezado/IdDoc/MedioPago"/></xsl:with-param>
                    <xsl:with-param name="formaPago"><xsl:value-of select="Encabezado/IdDoc/FmaPago"/></xsl:with-param>
                </xsl:apply-templates>
                <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                          fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" border-color="#1a86c8">
                    <fo:block-container height="12cm">
                        <fo:table table-layout="fixed" width="100%" border-collapse="collapse">
                            <fo:table-column column-width="0.5cm"/>
                            <fo:table-column column-width="10.17cm"/>
                            <fo:table-column column-width="1.5cm"/>
                            <fo:table-column column-width="1.85cm"/>
                            <fo:table-column column-width="1.6cm"/>
                            <fo:table-column column-width="1.68cm"/>
                            <!--
                            <fo:table-column column-width="1.5cm"/>
                            -->
                            <fo:table-column column-width="2.5cm"/>
                            <fo:table-header color="#ffffff" background-color="#1a86c8" font-weight="bold">
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">#</fo:block></fo:table-cell>
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">Glosa</fo:block></fo:table-cell>
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">Cantidad</fo:block></fo:table-cell>
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">Precio Unit.</fo:block></fo:table-cell>
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">Dcto/Rcrg</fo:block></fo:table-cell>
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">Afecto IVA</fo:block></fo:table-cell>
                                <!--
                                <fo:table-cell border-right-width=".8pt" border-right-color="#ffffff" border-right-style="solid" text-align="center"><fo:block margin="2pt">Imp. Esp.</fo:block></fo:table-cell>
                                -->
                                <fo:table-cell text-align="center"><fo:block margin="2pt">Monto</fo:block></fo:table-cell>
                            </fo:table-header>
                            <fo:table-body>
                                <xsl:choose>
                                    <xsl:when test="Detalle">
                                        <xsl:apply-templates select="Detalle"/>
                                    </xsl:when>
                                    <xsl:otherwise>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block>-</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block>-</fo:block></fo:table-cell>
                                        </fo:table-row>
                                    </xsl:otherwise>
                                </xsl:choose>
                            </fo:table-body>
                        </fo:table>
                    </fo:block-container>
                </fo:block>

                <fo:table table-layout="fixed">
                    <fo:table-column column-width="14.2cm"/>
                    <fo:table-column column-width="5.6cm"/>
                    <fo:table-body><fo:table-row>
                        <fo:table-cell><xsl:choose>
                            <xsl:when test="Referencia">
                                <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                          fox:border-radius="4pt" border-width="0.8pt" border-style="solid" border-color="#1a86c8" margin-right="2pt">
                                    <fo:table table-layout="fixed" margin="2pt">
                                        <fo:table-column column-width="2.84cm"/>
                                        <fo:table-column column-width="2.84cm"/>
                                        <fo:table-column column-width="2.84cm"/>
                                        <fo:table-column column-width="2.84cm"/>
                                        <fo:table-column column-width="2.84cm"/>
                                        <fo:table-header><fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold">Documento ref.</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block font-weight="bold">Folio</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block font-weight="bold">Fecha</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block font-weight="bold">Razón ref.</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block font-weight="bold">Tipo de oper.</fo:block></fo:table-cell>
                                        </fo:table-row></fo:table-header>
                                        <fo:table-body>
                                            <xsl:apply-templates select="Referencia"/>
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
                                              fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" border-color="#1a86c8" margin-right="2pt">
                                        <fo:table table-layout="fixed" width="100%">
                                            <fo:table-column column-width="50%"/>
                                            <fo:table-column column-width="50%"/>
                                            <fo:table-header background-color="#eaeaea"><fo:table-row>
                                                <fo:table-cell number-columns-spanned="2"><fo:block font-weight="bold" text-align="center" margin="4pt">ACUSE DE RECIBO</fo:block></fo:table-cell>
                                            </fo:table-row></fo:table-header>
                                            <fo:table-body>
                                                <fo:table-row border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea">
                                                    <fo:table-cell><fo:block font-weight="bold" margin="4pt">Nombre:</fo:block></fo:table-cell><fo:table-cell><fo:block font-weight="bold" margin="4pt">RUT:</fo:block></fo:table-cell></fo:table-row>
                                                <fo:table-row>
                                                    <fo:table-cell border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea"><fo:block font-weight="bold" margin="4pt">Fecha:</fo:block></fo:table-cell><fo:table-cell><fo:block font-weight="bold" margin="4pt">Firma:</fo:block></fo:table-cell></fo:table-row>
                                                <fo:table-row border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea">
                                                    <fo:table-cell><fo:block font-weight="bold" margin="4pt" margin-bottom="8pt">Recinto:</fo:block></fo:table-cell></fo:table-row>
                                                <fo:table-row>
                                                    <fo:table-cell number-columns-spanned="2"><fo:block font-size="7pt" margin="4pt">El acuse de recibo que se declara en este acto, de acuerdo a lo dispuesto en la letra b) del Art. 4° y la letra c) del Art. 5° de la Ley 19.983, acredita que la entrega de mercadería(s) o servicio(s) prestado(s) ha(n) sido recibido(s).</fo:block></fo:table-cell></fo:table-row>
                                            </fo:table-body>
                                        </fo:table>
                                    </fo:block>
                                    <fo:block text-align="right" font-size="9.01pt"><xsl:choose><xsl:when test="$tipoDocumento=52">CEDIBLE CON SU FACTURA&#160;&#160;</xsl:when><xsl:otherwise>CEDIBLE&#160;&#160;</xsl:otherwise></xsl:choose></fo:block>
                                </xsl:when>
                                <xsl:otherwise>
                                    <fo:block/>
                                </xsl:otherwise></xsl:choose>

                            <fo:block margin-top="1cm" font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                      fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" border-color="#1a86c8" margin-right="2pt">
                                <fo:table table-layout="fixed">
                                    <fo:table-column column-width="100%"/>
                                    <fo:table-header background-color="#eaeaea"><fo:table-row>
                                        <fo:table-cell><fo:block font-size="9.01pt" text-align="center" margin="4pt">Formas de pago:</fo:block></fo:table-cell>
                                    </fo:table-row></fo:table-header>
                                    <fo:table-body>
                                        <fo:table-row>
                                            <fo:table-cell>
                                                <fo:block font-weight="bold" margin-left="4pt" margin-top="4pt">Transferencia bancaria</fo:block>
                                                <fo:block margin-left="4pt">BCI - Cuenta corriente 21186952 Titular Kombuchacha SpA - RUT 76792536-0 <fo:basic-link external-destination="mailto:ventas@kombuchacha.cl">ventas@kombuchacha.cl</fo:basic-link></fo:block>
                                            </fo:table-cell>
                                        </fo:table-row>
                                    </fo:table-body>
                                </fo:table>
                            </fo:block>

                        </fo:table-cell>
                        <fo:table-cell>
                            <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                                      fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" border-color="#1a86c8" margin-left="2pt">
                                <fo:table table-layout="fixed">
                                    <fo:table-column column-width="3cm"/>
                                    <fo:table-column column-width="2.4cm"/>
                                    <fo:table-body>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">Descuento Global</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="DscRcgGlobal[NroLinDR=1]"><xsl:value-of select="format-number(DscRcgGlobal/ValorDR, '###.###','european')"/>%</xsl:when>
                                                <xsl:otherwise>-</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">Monto Neto</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="Encabezado/Totales/MntNeto">$ <xsl:value-of select="format-number(Encabezado/Totales/MntNeto, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">Monto Exento</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="Encabezado/Totales/MntExe">$ <xsl:value-of select="format-number(Encabezado/Totales/MntExe, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt">IVA <xsl:value-of select="format-number(Encabezado/Totales/TasaIVA, '###.###,##', 'european')"/>%</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt"><xsl:choose>
                                                <xsl:when test="Encabezado/Totales/MntNeto">$ <xsl:value-of select="format-number(Encabezado/Totales/IVA, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                        <fo:table-row>
                                            <fo:table-cell><fo:block font-weight="bold" margin="2pt" font-size="9.01pt">Total</fo:block></fo:table-cell>
                                            <fo:table-cell><fo:block text-align="right" margin="2pt" font-size="9.01pt"><xsl:choose>
                                                <xsl:when test="Encabezado/Totales/MntNeto">$ <xsl:value-of select="format-number(Encabezado/Totales/MntTotal, '###.###','european')"/></xsl:when>
                                                <xsl:otherwise>$ 0</xsl:otherwise>
                                            </xsl:choose></fo:block></fo:table-cell>
                                        </fo:table-row>
                                    </fo:table-body>
                                </fo:table>
                            </fo:block>
                            <xsl:apply-templates select="TED"/>
                        </fo:table-cell>
                    </fo:table-row></fo:table-body>
                </fo:table>


            </fo:block-container>

        </fo:block>
        <fo:block-container absolute-position="absolute" left="19.85cm" top="-7.8cm" reference-orientation="90">
            <fo:block writing-mode="tb-rl" font-size="6pt" color="#c0c0c0">Moit ERP <fo:basic-link external-destination="https://moit.cl/">www.moit.cl</fo:basic-link></fo:block>
        </fo:block-container>
    </xsl:template>

    <!-- Datos del emisor -->
    <xsl:template match="Emisor">
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

                        <fo:block font-size="10.81pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" text-align="left" color="#274c7b">
                            <xsl:value-of select="RznSoc"/>
                        </fo:block>

                        <xsl:if test="Sucursal">
                            <fo:block font-weight="bold" font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                                Sucursal: <xsl:value-of select="Sucursal"/> (Codigo SII: <xsl:value-of select="CdgSIISucur"/>)
                            </fo:block>
                        </xsl:if>

                        <fo:block font-size="9.01pt" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                            <xsl:value-of select="GiroEmis"/>
                        </fo:block>

                        <fo:block font-size="8.4pt" margin-top="1mm" font-family="Helvetica, Arial, sans-serif" language="es" hyphenate="true" color="black" text-align="left">
                            Casa Matriz: <xsl:value-of select="DirOrigen"/>, <xsl:value-of select="CmnaOrigen"/>
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
                                <fo:block font-size="10.21pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" color="red" text-align="center" hyphenate="false">R.U.T.:<xsl:call-template name="RutFormat">
                                    <xsl:with-param name="rut"><xsl:value-of select="RUTEmisor"/></xsl:with-param></xsl:call-template>
                                </fo:block>
                                <fo:block font-size="10.21pt" font-family="Helvetica, Arial, sans-serif" font-weight="bold" color="red" text-align="center">
                                    <xsl:choose>
                                        <xsl:when test="$tipo=33">FACTURA ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=34">FACTURA ELECTRÓNICA EXENTA</xsl:when>
                                        <xsl:when test="$tipo=52">GUIA DE DESPACHO ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=56">NOTA DE DEBITO ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=61">NOTA DE CREDITO ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=110">FACTURA DE EXPORTACION ELECTRÓNICA</xsl:when>
                                        <xsl:when test="$tipo=112">NOTA DE CREDITO EXPORTACION ELECTRÓNICA</xsl:when>
                                        <xsl:otherwise>CORREGIR EN TEMPLATE XSL</xsl:otherwise>
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
    <xsl:template match="Receptor">
        <xsl:param name="fecha"/>
        <xsl:param name="medioPago"/>
        <xsl:param name="formaPago"/>

        <fo:block-container absolute-position="auto">
            <fo:block font-size="8.4pt" font-family="Helvetica, Arial, sans-serif" space-after="2pt" language="es" hyphenate="true" color="black" text-align="left"
                      fox:border-radius="4pt" border-width="0.8pt"  border-style="solid" border-color="#1a86c8">
                <fo:table table-layout="fixed" margin="2pt">
                    <fo:table-column column-width="3.2cm"/>
                    <fo:table-column column-width="8cm"/>
                    <fo:table-column column-width="2cm"/>
                    <fo:table-column column-width="6.6cm"/>
                    <fo:table-body>
                        <fo:table-row>
                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Señor(es)</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:value-of select="RznSocRecep"/></fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline font-weight="bold">Fecha</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="2pt"><fo:inline><xsl:call-template name="FechaFormat"><xsl:with-param name="fecha"><xsl:value-of select="$fecha"/></xsl:with-param></xsl:call-template></fo:inline></fo:block></fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">RUT</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><xsl:call-template name="RutFormat"><xsl:with-param name="rut">
                                <xsl:value-of select="RUTRecep"/></xsl:with-param></xsl:call-template></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Dirección</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="DirRecep"/></fo:block></fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Giro</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="GiroRecep"/></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Comuna</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="CmnaRecep"/></fo:block></fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Contacto</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline></fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Ciudad</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><xsl:value-of select="CiudadRecep"/></fo:block></fo:table-cell>
                        </fo:table-row>
                        <fo:table-row>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Condiciones de pago</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><xsl:call-template name="PagoFormat">
                                <xsl:with-param name="medioPago"><xsl:value-of select="$medioPago"/></xsl:with-param>
                                <xsl:with-param name="formaPago"><xsl:value-of select="$formaPago"/></xsl:with-param>
                            </xsl:call-template></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline font-weight="bold">Vencimiento</fo:inline></fo:block></fo:table-cell>
                            <fo:table-cell><fo:block margin-top="4pt"><fo:inline></fo:inline></fo:block></fo:table-cell>
                        </fo:table-row>
                    </fo:table-body>
                </fo:table>
            </fo:block>

        </fo:block-container>
    </xsl:template>

<!-- Detalle -->
    <xsl:template match="Detalle">
        <fo:table-row border-bottom-width="1pt" border-bottom-style="solid" border-bottom-color="#eaeaea">
            <fo:table-cell text-align="right">
                <fo:block margin="2pt"><xsl:value-of select="NroLinDet"/></fo:block>
            </fo:table-cell>
            <fo:table-cell text-align="left">
                <fo:block margin="2pt">
                    <xsl:if test="VlrCodigo"><xsl:value-of select="VlrCodigo"/> -- </xsl:if>
                    <xsl:value-of select="NmbItem"/></fo:block>
                <xsl:if test="DscItem"><fo:block margin="2pt"><xsl:value-of select="DscItem"/></fo:block></xsl:if>
            </fo:table-cell>
            <fo:table-cell text-align="right">
                <fo:block margin="2pt"><xsl:value-of select="QtyItem"/>&#160;<xsl:value-of select="UnmdItem"/></fo:block>
            </fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                <xsl:choose>
                    <xsl:when test="string(number(PrcItem))='NaN'">0</xsl:when>
                    <xsl:otherwise>$&#160;<xsl:value-of select="format-number(PrcItem, '###.###', 'european')"/></xsl:otherwise>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                <xsl:choose>
                    <xsl:when test="string(number(DescuentoPct))='NaN'">0%</xsl:when>
                    <xsl:otherwise><xsl:value-of select="format-number(DescuentoPct, '###.###', 'european')"/>%</xsl:otherwise>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell>
                <fo:block margin="2pt">
                    <xsl:choose>
                        <xsl:when test="IndExe">NO</xsl:when>
                        <xsl:otherwise>SI</xsl:otherwise>
                    </xsl:choose>
                </fo:block>
            </fo:table-cell>
            <fo:table-cell text-align="right"><fo:block margin="2pt">
                $&#160;<xsl:value-of select="format-number(MontoItem, '###.###', 'european')"/>
            </fo:block></fo:table-cell>
        </fo:table-row>
    </xsl:template>

<!-- Referencias -->
    <xsl:template match="Referencia">
        <fo:table-row>
            <fo:table-cell text-align="left"><fo:block>
                <xsl:choose>
                    <xsl:when test="TpoDocRef=30">Factura</xsl:when>
                    <xsl:when test="TpoDocRef=32">Factura de Venta Bienes y Servicios</xsl:when>
                    <xsl:when test="TpoDocRef=33">Factura Electrónica</xsl:when>
                    <xsl:when test="TpoDocRef=34">Factura Electrónica Exenta</xsl:when>
                    <xsl:when test="TpoDocRef=35">Boleta</xsl:when>
                    <xsl:when test="TpoDocRef=38">Boleta Exenta</xsl:when>
                    <xsl:when test="TpoDocRef=52">Guía de Despacho Electrónica</xsl:when>
                    <xsl:when test="TpoDocRef=56">Nota de Débito Electrónica</xsl:when>
                    <xsl:when test="TpoDocRef=61">Nota de Crédito Electrónica</xsl:when>
                    <xsl:when test="TpoDocRef=801">Orden de Compra</xsl:when>
                    <xsl:when test="TpoDocRef=802">Nota de pedido</xsl:when>
                    <xsl:when test="TpoDocRef=803">Contrato</xsl:when>
                    <xsl:when test="TpoDocRef=804">Resolución</xsl:when>
                    <xsl:when test="TpoDocRef=805">Proceso ChileCompra</xsl:when>
                    <xsl:when test="TpoDocRef=806">Ficha ChileCompra</xsl:when>
                    <xsl:when test="TpoDocRef=807">DUS</xsl:when>
                    <xsl:when test="TpoDocRef=808">B/L (Conocimiento de embarque)</xsl:when>
                    <xsl:when test="TpoDocRef=809">AWB (Air Will Bill)</xsl:when>
                    <xsl:when test="TpoDocRef=810">MIC/DTA</xsl:when>
                    <xsl:when test="TpoDocRef=811">Carta de Porte</xsl:when>
                    <xsl:when test="TpoDocRef=812">Resolución del SNA donde califica Servicios de Exportación</xsl:when>
                    <xsl:when test="TpoDocRef=813">Pasporte</xsl:when>
                    <xsl:when test="TpoDocRef=814">Certificado de Depósito Bolsa Prod. Chile</xsl:when>
                    <xsl:when test="TpoDocRef=815">Vale de Prenda Bolsa Prod. Chile</xsl:when>
                    <xsl:when test="TpoDocRef=820">Código de Inscripción en el Registro de Acuerdos con Plazo de Pago Excepcional</xsl:when>
                    <xsl:otherwise>Tipo <xsl:value-of select="TpoDocRef"/></xsl:otherwise>
                </xsl:choose>
            </fo:block></fo:table-cell>
            <fo:table-cell><fo:block><xsl:value-of select="FolioRef"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block><xsl:value-of select="FchRef"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block><xsl:value-of select="RazonRef"/></fo:block></fo:table-cell>
            <fo:table-cell><fo:block>
                <xsl:choose>
                    <xsl:when test="CodRef=1">Anula Doc. Ref.</xsl:when>
                    <xsl:when test="CodRef=2">Corrige Texto</xsl:when>
                    <xsl:when test="CodRef=3">Corrige Montos</xsl:when>
                </xsl:choose>
            </fo:block></fo:table-cell>
        </fo:table-row>
    </xsl:template>

    <!-- Timbre electrónico -->
    <xsl:template match="TED">
        <xsl:variable name="myted" select="." />
        <fo:block-container>
            <fo:block><fo:instream-foreign-object scaling="uniform" content-width="5.6cm"><xsl:copy-of select="tedbarcode:generate($myted)"/></fo:instream-foreign-object></fo:block>
            <fo:block font-size="7pt" font-family="sans-serif" text-align="center">Timbre Electrónico SII</fo:block>
            <fo:block font-size="7pt" font-family="sans-serif" text-align="center">Resolución Ex. SII N° <xsl:value-of select="$nroResol"/> de <xsl:value-of select="$fchResol"/> - Verifique Documento: www.sii.cl</fo:block>
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
            <xsl:when test="$formaPago=2"> (Crédito)</xsl:when>
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

</xsl:stylesheet>


