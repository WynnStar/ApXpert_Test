package tw.holdings.quote;

public enum Market {
    TWSE("上市"), TPEX("上櫃");

    private final String label;

    Market(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
