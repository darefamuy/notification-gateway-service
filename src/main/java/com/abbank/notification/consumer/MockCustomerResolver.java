package com.abbank.notification.consumer;

import com.abbank.notification.model.CustomerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Mock {@link CustomerResolver} for local development and unit/integration testing.
 *
 * <p>Generates deterministic contact details from the {@code accountId} so
 * that tests are predictable and repeatable without a live customer service.
 * Phone numbers are in E.164 format with the +234 Nigerian country code.
 *
 * <p>Replace with {@link HttpCustomerResolver} in production.
 */
public class MockCustomerResolver implements CustomerResolver {

    private static final Logger LOG = LoggerFactory.getLogger(MockCustomerResolver.class);

    // Simulated fixture accounts — extend for richer test scenarios
    private static final CustomerProfile[] FIXTURES = {
        new CustomerProfile(1001L, 100001L, "Adaeze",  "Okafor",   "adaeze.okafor@email.com",  "+2348031001001"),
        new CustomerProfile(1002L, 100002L, "Emeka",   "Nwosu",    "emeka.nwosu@email.com",    "+2348031002002"),
        new CustomerProfile(1003L, 100003L, "Ngozi",   "Eze",      "ngozi.eze@email.com",      "+2348031003003"),
        new CustomerProfile(1004L, 100004L, "Tunde",   "Adeyemi",  "tunde.adeyemi@email.com",  "+2348031004004"),
        new CustomerProfile(1005L, 100005L, "Chisom",  "Obi",      "chisom.obi@email.com",     "+2348031005005"),
    };

    @Override
    public Optional<CustomerProfile> resolve(final Long accountId) {
        if (accountId == null) return Optional.empty();

        // Check fixtures first
        for (final CustomerProfile fixture : FIXTURES) {
            if (fixture.getAccountId().equals(accountId)) {
                LOG.debug("MockCustomerResolver: found fixture for accountId={}", accountId);
                return Optional.of(fixture);
            }
        }

        // Deterministic generation for any other account ID
        final long   customerId = accountId + 900_000L;
        final long   suffix     = accountId % 10_000;
        final String firstName  = FIRST_NAMES[(int) (accountId % FIRST_NAMES.length)];
        final String lastName   = LAST_NAMES[(int)  ((accountId / 10) % LAST_NAMES.length)];
        final String email      = (firstName + "." + lastName + suffix + "@abbank-demo.com").toLowerCase();
        // Nigerian phone: +234 8XX XXX XXXX (MTN range)
        final String phone      = "+2348" + String.format("%09d", accountId % 1_000_000_000L);

        LOG.debug("MockCustomerResolver: generated profile for accountId={} customerId={}", accountId, customerId);
        return Optional.of(new CustomerProfile(
                customerId, accountId, firstName, lastName, email, phone));
    }

    private static final String[] FIRST_NAMES = {
        "Amaka", "Chidi", "Fatima", "Ibrahim", "Kemi",
        "Lanre", "Mercy", "Nnamdi", "Ola", "Peace",
        "Raheem", "Sade", "Tobi", "Uche", "Wale"
    };

    private static final String[] LAST_NAMES = {
        "Adebayo", "Adekunle", "Afolabi", "Agbo", "Ajayi",
        "Akindele", "Bello", "Dike", "Eze", "Fasanya",
        "Hassan", "Ihejirika", "Jibrin", "Lawal", "Nwachukwu"
    };
}
