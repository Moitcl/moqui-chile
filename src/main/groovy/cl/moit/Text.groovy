package cl.moit

import java.math.RoundingMode

class Text {

    public static void main(String[] argv) {
        print numberToText(new BigInteger(argv[0]))
    }

    public static String numberToText(BigDecimal number) {
        return numberToText(number.toBigInteger())
    }

    public static String numberToText(BigInteger number) {
        if (number == 0) return "cero"
        return numberToTextInternal(number)
    }

    public static String numberToTextInternal(BigInteger number) {
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
                case 1: text.append("dieci"); union = ""; break
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
        throw new RuntimeException("Number is out of range: ${number}")
    }

    public static String timeDurationToText(BigDecimal milliseconds) {
        return timeDurationToText(milliseconds, true)
    }
    public static String timeDurationToText(BigDecimal milliseconds, boolean precise) {
        return timeDurationToText(milliseconds, precise, false)
    }
    public static String timeDurationToText(BigDecimal milliseconds, boolean precise, boolean previousPrecise) {
        if (milliseconds == null)
            return null
        if (milliseconds < 2)
            milliseconds = milliseconds.setScale(3, RoundingMode.HALF_UP)
        else
            milliseconds = milliseconds.setScale(0, RoundingMode.HALF_UP)
        Long upperConversion = 1000L
        if (milliseconds < upperConversion) {
            return milliseconds.toString() + " ms"
        }
        Long conversion = upperConversion
        upperConversion = upperConversion*60
        if (milliseconds < upperConversion) {
            Long remainingMilliseconds = (milliseconds as Long) % conversion
            Long upperAmount = (milliseconds as Long) / conversion
            return upperAmount.toString() + (precise? ' seg' : (upperAmount > 1? ' segundos' : ' segundo')) + (((precise || !previousPrecise) && remainingMilliseconds > 0) ? ' ' + timeDurationToText(remainingMilliseconds, precise, true) : '')
        }
        conversion = upperConversion
        upperConversion = upperConversion*60
        if (milliseconds < upperConversion) {
            Long remainingMilliseconds = (milliseconds as Long) % conversion
            Long upperAmount = (milliseconds as Long) / conversion
            return upperAmount.toString() + (precise? ' min' : (upperAmount > 1? ' minutos' : ' minuto')) + (((precise || !previousPrecise) && remainingMilliseconds > 0) ? ' ' + timeDurationToText(remainingMilliseconds, precise, true) : '')
        }
        conversion = upperConversion
        upperConversion = upperConversion*24
        if (milliseconds < upperConversion) {
            Long remainingMilliseconds = (milliseconds as Long) % conversion
            Long upperAmount = (milliseconds as Long) / conversion
            return upperAmount.toString() + (precise? ' hr' : (upperAmount > 1 ? ' horas' : ' hora')) + (((precise || !previousPrecise) && remainingMilliseconds > 0) ? ' ' + timeDurationToText(remainingMilliseconds, precise, true) : '')
        }
        conversion = upperConversion
        upperConversion = upperConversion*365
        if (milliseconds < upperConversion) {
            Long remainingMilliseconds = (milliseconds as Long) % conversion
            Long upperAmount = (milliseconds as Long) / conversion
            return upperAmount.toString() + (precise? ' d' : (upperAmount > 1 ? ' días' : ' día')) + (((precise || !previousPrecise) && remainingMilliseconds > 0) ? ' ' + timeDurationToText(remainingMilliseconds, precise, true) : '')
        }
        conversion = upperConversion
        Long remainingMilliseconds = (milliseconds as Long) % conversion
        Long upperAmount = (milliseconds as Long) / conversion
        return upperAmount.toString() + (precise? ' a' : (upperAmount > 1 ? ' años' : ' año')) + (((precise || !previousPrecise) && remainingMilliseconds > 0) ? ' ' + timeDurationToText(remainingMilliseconds, precise, true) : '')
    }

}
