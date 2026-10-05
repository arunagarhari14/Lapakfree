package com.lapakfree;

/** Ek ride offer ka parsed data. -1 ka matlab "pata nahi chala". */
public class Offer {
    public String app = "";
    public double fare = -1;      // rupees
    public double pickupKm = -1;  // pickup kitna door
    public double tripKm = -1;    // trip kitni lambi

    @Override
    public String toString() {
        return "fare=" + (fare < 0 ? "?" : "₹" + (int) fare)
            + " pickup=" + (pickupKm < 0 ? "?" : pickupKm + "km")
            + " trip=" + (tripKm < 0 ? "?" : tripKm + "km");
    }
}
