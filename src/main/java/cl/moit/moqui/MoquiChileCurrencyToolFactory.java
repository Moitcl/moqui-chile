package cl.moit.moqui;

import org.moqui.context.ExecutionContextFactory;
import org.moqui.context.ToolFactory;

public class MoquiChileCurrencyToolFactory implements ToolFactory<MoquiChileCurrencyToolFactory> {

    private boolean initialized = false;

    public void init(ExecutionContextFactory ecf) {
        if (initialized) return;
        // Unidad Tributaria Mensual
        ecf.getLogger().info("MoquiChile CurrencyToolFactory adding Unidad Tributaria Mensual (CLM)");
        ecf.getL10n().addCurrency("CLM", Integer.valueOf(4), "Unidad Tributaria Mensual");
        // Unidad Tributaria Anual
        ecf.getLogger().info("MoquiChile CurrencyToolFactory adding Unidad Tributaria Anual (CLA)");
        ecf.getL10n().addCurrency("CLA", Integer.valueOf(4), "Unidad Tributaria Anual");
        initialized = true;
    }

    @Override
    public MoquiChileCurrencyToolFactory getInstance(Object... parameters) {
        return null;
    }

}
