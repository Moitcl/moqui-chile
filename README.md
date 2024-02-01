# Localization for Chile (Spanish) for Moqui

As localization, it includes not only translations but also a whole array of information and even business logic and functionality.

The functionality includes:

* Translations for framework, simplescreens and most relevant components
* National and Local Holidays
* Local currency / fund code: Unidad de Fomento (CLF, present in ISO-4217), as well as non-ISO-4217: Unidad Tributaria Mensual (CLM), Unidad Tributaria Anual (CLA)
* Order Tax Calculation
* DTE (Documentos Tributarios Electrónicos): data model, screens and services with functionality to generate, import, validate and process DTEs
* Integration with SII (Servicio de Impuestos Internos)
* Integration with Banco Central de Chile for diverse indices, including several currency exchange rates among CLP, CLF, CLM, CLA, EUR and USD
* Basic useful data definitions such as banking institutions, 

## Getting Started

This repository is meant to be used as a component within a Moqui Framework instance.

For details about moqui, its usage and installation, see https://moqui.org/

To include in a working moqui installation, add this repository into the runtime/components directory build, load seed data and run.