<?xml version="1.0" encoding="UTF-8"?>

<xsl:stylesheet version="1.1"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
	xmlns:fo="http://www.w3.org/1999/XSL/Format"
	xmlns:tedbarcode="cl.nic.dte.fop.TedBarcodeExtension"
	extension-element-prefixes="tedbarcode"
	>

	<xsl:output method="xml" version="1.0" omit-xml-declaration="no"
		indent="yes" />

	<xsl:decimal-format name="us" decimal-separator='.' grouping-separator=',' />
	<xsl:decimal-format name="european" decimal-separator=',' grouping-separator='.' />
	<xsl:decimal-format name="example" decimal-separator="." grouping-separator=","
	   infinity="INFINITY" minus-sign="-" NaN="Not a Number" percent="%"
	   per-mille="m" zero-digit="0" digit="#" pattern-separator=";" /> 

	<xsl:param name="versionParam" select="'1.0'" />
	<xsl:template match="/">
		<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
			<fo:layout-master-set>
				<fo:simple-page-master master-name="simple"
					page-height="27.9cm" page-width="21.6cm" margin-top="1cm"
					margin-bottom="2cm" margin-left="1cm" margin-right="1cm">
					<fo:region-body margin-top="0cm" />
					<fo:region-before extent="3cm" />
					<fo:region-after extent="1.5cm" />
				</fo:simple-page-master>
			</fo:layout-master-set>


			<fo:page-sequence master-reference="simple">
				<fo:flow flow-name="xsl-region-body">
					<xsl:apply-templates select="EnvioBOLETA/SetDTE/DTE/Documento" />
				</fo:flow>
			</fo:page-sequence>

		</fo:root>
	</xsl:template>


	<xsl:template match="EnvioBOLETA/SetDTE/DTE/Documento">
		<fo:block>
			<xsl:apply-templates select="Encabezado/Emisor">
				<xsl:with-param name="folio">
					<xsl:value-of select="Encabezado/IdDoc/Folio" />
				</xsl:with-param>
				<xsl:with-param name="tipo">
					<xsl:value-of select="Encabezado/IdDoc/TipoDTE" />
				</xsl:with-param>
			</xsl:apply-templates>
			<xsl:apply-templates select="Encabezado/Receptor">
				<xsl:with-param name="fecha">
					<xsl:value-of select="Encabezado/IdDoc/FchEmis" />
				</xsl:with-param>
				<xsl:with-param name="medioPago">
					<xsl:value-of select="Encabezado/IdDoc/MedioPago" />
				</xsl:with-param>
				<xsl:with-param name="formaPago">
					<xsl:value-of select="Encabezado/IdDoc/FmaPago" />
				</xsl:with-param>
			</xsl:apply-templates>

			<!--  La lista de detalle -->
			<fo:block-container absolute-position="absolute" left="0cm"
				top="5cm">
				<fo:block font-size="8pt" font-family="monospace"
					color="black" text-align="left" space-before="8pt">
					<fo:table table-layout="fixed" width="100%"
						border-collapse="collapse">
						<fo:table-column column-width="2cm" />
						<fo:table-column column-width="9.5cm" />
						<fo:table-column column-width="2.5cm" />
						<fo:table-column column-width="2.5cm" />
						<fo:table-column column-width="2.5cm" />

						<fo:table-body>
							<fo:table-row>
								<fo:table-cell text-align="center"
									border-width="0.5pt" border-style="solid">
									<fo:block>
										<fo:inline font-weight="bold">
											Cantidad
										</fo:inline>
									</fo:block>
								</fo:table-cell>
								<fo:table-cell text-align="center"
									border-width="0.5pt" border-style="solid">
									<fo:block>
										<fo:inline font-weight="bold">
											Detalle
										</fo:inline>
									</fo:block>
								</fo:table-cell>
								<fo:table-cell text-align="center"
									border-width="0.5pt" border-style="solid">
									<fo:block>
										<fo:inline font-weight="bold">
											P. Unitario
										</fo:inline>
									</fo:block>
								</fo:table-cell>
								<fo:table-cell text-align="center"
                                                                        border-width="0.5pt" border-style="solid">
                                                                        <fo:block>
                                                                                <fo:inline font-weight="bold">
                                                                                        Afecto IVA
                                                                                </fo:inline>
                                                                        </fo:block>
                                                                </fo:table-cell>
								<fo:table-cell text-align="center"
									border-width="0.5pt" border-style="solid">
									<fo:block>
										<fo:inline font-weight="bold">
											Total
										</fo:inline>
									</fo:block>
								</fo:table-cell>
							</fo:table-row>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=1]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=1]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=2]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=2]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=3]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=3]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=4]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=4]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=5]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=5]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=6]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=6]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=7]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=7]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=8]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=8]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when test="Detalle[NroLinDet=9]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=9]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when
									test="Detalle[NroLinDet=10]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=10]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when
									test="Detalle[NroLinDet=11]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=11]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when
									test="Detalle[NroLinDet=12]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=12]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when
									test="Detalle[NroLinDet=13]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=13]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when
									test="Detalle[NroLinDet=14]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=14]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>
							<xsl:choose>
								<xsl:when
									test="Detalle[NroLinDet=15]">
									<xsl:apply-templates
										select="Detalle[NroLinDet=15]" />
								</xsl:when>
								<xsl:otherwise>
									<xsl:call-template
										name="DetalleVacio" />
								</xsl:otherwise>
							</xsl:choose>

	
							<fo:table-row>
								<fo:table-cell text-align="center"
									border-left-width="0.5pt" border-left-style="solid"
									border-right-width="0.5pt" border-right-style="solid"
									border-bottom-width="0.5pt" border-bottom-style="solid"
									number-columns-spanned="5">Prueba
									<fo:block />
								</fo:table-cell>
							</fo:table-row>
							<fo:table-row>
								<fo:table-cell text-align="center"
									border-width="0.5pt" border-style="solid" display-align="center" column-number="4" height="1cm">
									<fo:block>
										<fo:inline font-weight="bold">
											Total
										</fo:inline>
									</fo:block>
								</fo:table-cell>
								<fo:table-cell text-align="center"
									border-width="0.5pt" border-style="solid" column-number="5" display-align="center" height="1cm">
									<fo:block>
										<fo:inline font-weight="bold">
											<xsl:value-of select="format-number(Encabezado/Totales/MntTotal, '###.###', 'european')"/>
										</fo:inline>
									</fo:block>
								</fo:table-cell>
							</fo:table-row>
						</fo:table-body>
					</fo:table>
				</fo:block>
			</fo:block-container>

			<xsl:apply-templates select="TED" />
		</fo:block>
	</xsl:template>


	<!-- Datos del emisor -->
	<xsl:template match="Emisor">
		<xsl:param name="folio" />
		<xsl:param name="tipo" />

		<!--  El logo -->
		<fo:block-container absolute-position="absolute" left="0cm"
			top="0cm">
			<fo:block>
				<fo:external-graphic
					src="url('/home/cherrera/git/moqui-framework/runtime/component/moqui-chile/DTE/TEMPLATES/logo-emisor.jpg')" width="30%" content-height="30%" content-width="scale-to-fit" scaling="uniform"/>
			</fo:block>
		</fo:block-container>

		<fo:block-container absolute-position="absolute" left="2.5cm"
			top="0cm" width="9cm">

			<fo:block font-size="18pt" font-family="Helvetica"
				font-weight="bold" text-align="left" color="blue">
				<xsl:value-of select="RznSocEmisor" />
			</fo:block>

			<xsl:if test="Sucursal">
				<fo:block font-weight="bold" font-size="12pt" font-family="monospace"
					language="es" hyphenate="true" color="black" text-align="left">
					Sucursal: <xsl:value-of select="Sucursal" /> (Codigo SII: <xsl:value-of select="CdgSIISucur" />)
				</fo:block>
			</xsl:if>

			<fo:block font-weight="bold" font-size="12pt" font-family="monospace"
				language="es" hyphenate="true" color="black" text-align="left">
				<xsl:value-of select="GiroEmisor" />
			</fo:block>

			<fo:block font-weight="bold" font-size="12pt" font-family="monospace"
				language="es" hyphenate="true" color="black" text-align="left">
				<xsl:value-of select="DirOrigen" />
			</fo:block>

			<fo:block font-weight="bold" font-size="12pt" font-family="monospace"
				language="es" hyphenate="true" color="black" text-align="left">
				<xsl:value-of select="CmnaOrigen" />
				-
				<xsl:value-of select="CiudadOrigen" />
			</fo:block>

		</fo:block-container>

		<!-- Recuadro con folio -->
		<fo:block-container absolute-position="absolute" top="0cm"
			margin-top="0.5cm" left="12cm" height="3cm" width="7.5cm"
			border-color="red" border-style="solid" border-width="0.5mm">
			<fo:block font-size="14pt" font-family="monospace"
				font-weight="bold" color="red" text-align="center"
				hyphenate="false">
				R.U.T.:
				<xsl:call-template name="RutFormat">
					<xsl:with-param name="rut">
						<xsl:value-of select="RUTEmisor" />
					</xsl:with-param>
				</xsl:call-template>
			</fo:block>
			<fo:block font-size="14pt" font-family="monospace"
				font-weight="bold" color="red" text-align="center">
				<xsl:choose>
					<xsl:when test="$tipo=39">
                                                BOLETA ELECTRONICA
                                        </xsl:when>
                                        <xsl:when test="$tipo=41">
                                                BOLETA ELECTRONICA EXENTA
                                        </xsl:when>
					<xsl:otherwise>
						CORREGIR EN TEMPLATE XSL
					</xsl:otherwise>
				</xsl:choose>
			</fo:block>

			<fo:block font-size="14pt" font-family="monospace"
				font-weight="bold" color="red" text-align="center">
				N&#176;
				<xsl:value-of select="$folio" />
			</fo:block>
		</fo:block-container>
		<!--fo:block-container absolute-position="absolute" top="3cm"
							margin-top="0.5cm" left="12cm" height="3cm" width="7.5cm">
     		<fo:block font-size="12pt" font-family="monospace"
				  font-weight="bold" color="red" text-align="center">
			S.I.I. - Santiago Oriente
		</fo:block>
		</fo:block-container-->

	</xsl:template>

	<!-- Datos del receptor -->
	<xsl:template match="Receptor">
		<xsl:param name="fecha" />
		<xsl:param name="medioPago"/>
		<xsl:param name="formaPago"/>

     	<fo:block-container absolute-position="absolute" left="0cm"
			top="4cm">
			<fo:block font-size="10pt" font-family="monospace" space-after="8pt"
				language="es" hyphenate="true" color="black" text-align="left">
				Santiago,
				<xsl:call-template name="FechaFormat">
					<xsl:with-param name="fecha">
						<xsl:value-of select="$fecha" />
					</xsl:with-param>
				</xsl:call-template>

			</fo:block>
		
			<fo:block font-size="10pt" font-family="monospace"
				language="es" hyphenate="true" color="black" text-align="left">

			</fo:block>
</fo:block-container>
	</xsl:template>

<!-- Detalle -->
	<xsl:template match="Detalle">
		<fo:table-row >
			<fo:table-cell text-align="right" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" margin-right="2mm"  height="0.8cm">
				<fo:block>
						<xsl:value-of select="QtyItem" />&#160;<xsl:value-of select="UnmdItem" />
				</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="left" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid"  margin-right="2mm" margin-left="2mm"  height="0.8cm">
				<fo:block >
						<xsl:value-of select="NmbItem" />
				</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="right" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" margin-right="2mm"  height="0.8cm">
				<fo:block>
						<xsl:value-of select="format-number(PrcItem, '###.###', 'european')" />
				</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="right" border-left-width="0.5pt"
                                border-left-style="solid" border-right-width="0.5pt"
                                border-right-style="solid" margin-right="2mm"  height="0.8cm">
                                <fo:block>
                                                <!--xsl:value-of select="IndExe" /-->
						 <xsl:choose>
		                                        <xsl:when test="IndExe=1">
                                		                NO
                		                        </xsl:when>
		                                        <xsl:otherwise>
                                		                SI
                		                        </xsl:otherwise>
		                                </xsl:choose>
                                </fo:block>
                        </fo:table-cell>
			<fo:table-cell text-align="right" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" margin-right="2mm" height="0.8cm" >
				<fo:block>
						<xsl:value-of select="format-number(MontoItem, '###.###', 'european')"/>
				</fo:block>
			</fo:table-cell>
		</fo:table-row>
	</xsl:template>


	<!-- Timbre electrónico -->
	<xsl:template match="TED">
		<xsl:variable name="myted" select="." />
		<xsl:variable name="barcode-cfg">
			<barcode>
				<!--  Segun SII, 3cm x 9cm max -->
				<pdf417>
					<module-width>0.008in</module-width>
					<!--  min exigido por Sii 0.0067  -->
					<row-height>3mw</row-height>
					<!--  3 veces el ancho -->
					<quite-zone enabled="true">0.25in</quite-zone>
					<ec-level>5</ec-level>
					<columns>14</columns>
				</pdf417>
			</barcode>
		</xsl:variable>
		<fo:block-container absolute-position="absolute" top="21cm"
			width="7cm">
			<fo:block>
				<fo:instream-foreign-object>
			
					<xsl:copy-of
						select="tedbarcode:generate($barcode-cfg, $myted)" />
			
				</fo:instream-foreign-object>
			</fo:block>
			<fo:block font-size="8pt" font-family="sans-serif"
				text-align="center">
				Timbre Electrónico SII
			</fo:block>
			<fo:block font-size="8pt" font-family="sans-serif"
				text-align="center">
				Res. 99 de 2014 - Verifique Documento: https://mtest.moit.cl/dte
			</fo:block>
		</fo:block-container>
	</xsl:template>

	<xsl:template name="PagoFormat">
		<xsl:param name="medioPago" />
		<xsl:param name="formaPago" />

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
		<xsl:param name="fecha" />

		<xsl:value-of
			select="substring($fecha,string-length($fecha)-1,2)" />
		de
		<xsl:choose>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=01">
				Enero
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=02">
				Febrero
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=03">
				Marzo
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=04">
				Abril
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=05">
				Mayo
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=06">
				Junio
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=07">
				Julio
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=08">
				Agosto
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=09">
				Septiembre
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=10">
				Octubre
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=11">
				Noviembre
			</xsl:when>
			<xsl:when
				test="substring($fecha,string-length($fecha)-4,2)=12">
				Diciembre
			</xsl:when>
		</xsl:choose>
		de
		<xsl:value-of
			select="substring($fecha,string-length($fecha)-9,4)" />
	</xsl:template>

	<xsl:template name="RutFormat">
		<xsl:param name="rut" />
		<xsl:variable name="num" select="substring-before($rut,'-')" />
		<xsl:variable name="dv" select="substring-after($rut,'-')" />

		<xsl:value-of select="substring($num,string-length($num)-8,3)" />.<xsl:value-of
		 select="substring($num,string-length($num)-5,3)" />.<xsl:value-of 
		 select="substring($num,string-length($num)-2,3)" />-<xsl:value-of select="$dv" />

	</xsl:template>

	<xsl:template name="DetalleVacio">
		<fo:table-row>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
                                border-left-style="solid" border-right-width="0.5pt"
                                border-right-style="solid" height="0.8cm">
                                <fo:block white-space-treatment="preserve">&#xa0;</fo:block>
                        </fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
				border-left-style="solid" border-right-width="0.5pt"
				border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
		</fo:table-row>
	</xsl:template>

	<xsl:template name="ReferenciaVacio">
		<fo:table-row>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
						   border-left-style="solid" border-right-width="0.5pt"
						   border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
						   border-left-style="solid" border-right-width="0.5pt"
						   border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
						   border-left-style="solid" border-right-width="0.5pt"
						   border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
						   border-left-style="solid" border-right-width="0.5pt"
						   border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
						   border-left-style="solid" border-right-width="0.5pt"
						   border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
                                                   border-left-style="solid" border-right-width="0.5pt"
                                                   border-right-style="solid" height="0.8cm">
                                <fo:block white-space-treatment="preserve">&#xa0;</fo:block>
                        </fo:table-cell>
			<fo:table-cell text-align="center" border-left-width="0.5pt"
						   border-left-style="solid" border-right-width="0.5pt"
						   border-right-style="solid" height="0.8cm">
				<fo:block white-space-treatment="preserve">&#xa0;</fo:block>
			</fo:table-cell>
		</fo:table-row>

	</xsl:template>

</xsl:stylesheet>


