package com.abbank.notification.model;

/**
 * Resolved customer contact details for a given account.
 *
 * <p>Obtained from the {@link com.abbank.notification.consumer.CustomerResolver}.
 * Only the fields required for notification dispatch are held here.
 */
public class CustomerProfile {

    private final Long   customerId;
    private final Long   accountId;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final String phone;   // E.164 format, e.g. "+2348031234567"

    public CustomerProfile(
            final Long customerId,
            final Long accountId,
            final String firstName,
            final String lastName,
            final String email,
            final String phone) {
        this.customerId = customerId;
        this.accountId  = accountId;
        this.firstName  = firstName;
        this.lastName   = lastName;
        this.email      = email;
        this.phone      = phone;
    }

    public Long   getCustomerId() { return customerId; }
    public Long   getAccountId()  { return accountId; }
    public String getFirstName()  { return firstName; }
    public String getLastName()   { return lastName; }
    public String getFullName()   { return firstName + " " + lastName; }
    public String getEmail()      { return email; }
    public String getPhone()      { return phone; }

    /** Returns true only if this profile has a non-blank email. */
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    /** Returns true only if this profile has a non-blank phone number. */
    public boolean hasPhone() {
        return phone != null && !phone.isBlank();
    }

    @Override
    public String toString() {
        return "CustomerProfile{customerId=" + customerId
             + ", accountId=" + accountId
             + ", email=" + (email != null ? email.replaceAll("(?<=.{3}).(?=.*@)", "*") : "null")
             + ", phone=" + (phone != null ? phone.substring(0, Math.min(6, phone.length())) + "***" : "null")
             + "}";
    }
}
