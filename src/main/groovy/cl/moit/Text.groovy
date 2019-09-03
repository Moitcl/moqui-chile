package cl.moit

class Text {

    public static void main(String[] argv) {
        print numberToText(Long.parseLong(argv[0]))
    }

    public static String numberToText(Long number) {
        if (number == 0) return "cero"
        return numberToTextInternal(number).toString()
    }

    public static String numberToTextInternal(Long number) {
        if (number < 0) return "menos " + numberToTextInternal(-number)
        if (number < 16) {
            switch(number) {
                case 0: return ""
                case 1: return "un"
                case 2: return "dos"
                case 3: return "tres"
                case 4: return "cuatro"
                case 5: return "cinco"
                case 6: return "seis"
                case 7: return "siete"
                case 8: return "ocho"
                case 9: return "nueve"
                case 10: return "diez"
                case 11: return "once"
                case 12: return "doce"
                case 13: return "trece"
                case 14: return "catorce"
                case 15: return "quince"
            }
        }
        StringBuilder text = new StringBuilder()
        if (number < 100) {
            if (number == 20) return "veinte"
            int decena = number / 10
            int resto = number % 10
            String union = " y "
            switch(decena) {
                case 2: text.append("veinti"); union = ""; break
                case 3: text.append("treinta"); break
                case 4: text.append("cuarenta"); break
                case 5: text.append("cincuenta"); break
                case 6: text.append("sesenta"); break
                case 7: text.append("setenta"); break
                case 8: text.append("ochenta"); break
                case 9: text.append("noventa"); break
            }
            if (resto) text.append(union + numberToTextInternal(resto))
            return text.toString()
        }
        if (number < 1000) {
            int centena = number / 100
            int resto = number % 100
            String union = " "
            switch(centena) {
                case 1: text.append("cien"); union = "to "; break
                case 5: text.append("quinientos"); break
                case 7: text.append("setecientos"); break
                case 9: text.append("novecientos"); break
                default:
                text.append(numberToTextInternal(centena))
                text.append("cientos")
            }
            if (resto) text.append(union); text.append(numberToTextInternal(resto))
            return text.toString()
        }
        if (number < 1000000) {
            int miles = number / 1000
            int resto = number % 1000
            if (miles != 1) {
                text.append(numberToTextInternal(miles))
                text.append(" ")
            }
            text.append("mil")
            if (resto == 1)
                text.append(" y un")
            else if (resto) {
                text.append(" ")
                text.append(numberToTextInternal(resto))
            }
            return text.toString()
        }
        if (number < 1000000000000) {
            int millones = number / 1000000
            int resto = number % 1000000
            if (millones == 1) text.append("un millón")
            else {
                text.append(numberToTextInternal(millones))
                text.append(" millones")
            }
            if (resto == 1)
                text.append(" y un")
            else if (resto) {
                text.append(" ")
                text.append(numberToTextInternal(resto))
            }
            return text.toString()
        }
    }
}
