package com.sti.tanghap.data;

public class HealthProfile {
    private boolean hasAsthma;
    private boolean hasCOPD;
    private boolean hasBronchitis;
    private boolean hasMildCongestion;
    private String inhalerUsage;

    public HealthProfile() {}

    public HealthProfile(boolean hasAsthma, boolean hasCOPD, boolean hasBronchitis, boolean hasMildCongestion, String inhalerUsage) {
        this.hasAsthma = hasAsthma;
        this.hasCOPD = hasCOPD;
        this.hasBronchitis = hasBronchitis;
        this.hasMildCongestion = hasMildCongestion;
        this.inhalerUsage = inhalerUsage;
    }

    public boolean isHasAsthma() { return hasAsthma; }
    public void setHasAsthma(boolean hasAsthma) { this.hasAsthma = hasAsthma; }

    public boolean isHasCOPD() { return hasCOPD; }
    public void setHasCOPD(boolean hasCOPD) { this.hasCOPD = hasCOPD; }

    public boolean isHasBronchitis() { return hasBronchitis; }
    public void setHasBronchitis(boolean hasBronchitis) { this.hasBronchitis = hasBronchitis; }

    public boolean isHasMildCongestion() { return hasMildCongestion; }
    public void setHasMildCongestion(boolean hasMildCongestion) { this.hasMildCongestion = hasMildCongestion; }

    public String getInhalerUsage() { return inhalerUsage; }
    public void setInhalerUsage(String inhalerUsage) { this.inhalerUsage = inhalerUsage; }
}
