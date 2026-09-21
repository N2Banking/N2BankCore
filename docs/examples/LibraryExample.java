import com.n2bank.bootstrap.BankApplication;
import com.n2bank.bootstrap.BankApplication.OperationMetadata;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/** Documentation example; writes demo data to the configured database. */
public class LibraryExample {
    public static void main(String[] args) {
        Currency euro = Currency.getInstance("EUR");
        UUID cashId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID revenueId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        try (BankApplication bank = BankApplication.initializeFromEnvironment()) {
            bank.registerSystemAccounts(euro, cashId, revenueId);
            bank.ensureSystemAccounts(euro, "Example bank cash", "Example fee revenue");
            Customer alice = bank.createCustomer(
                new Customer(UUID.randomUUID(), "Alice", CustomerType.PERSON));
            Customer bob = bank.createCustomer(
                new Customer(UUID.randomUUID(), "Bob", CustomerType.PERSON));
            Account aliceAccount = bank.openAccount(new Account(
                UUID.randomUUID(), "Alice EUR", Optional.of(alice), AccountType.LIABILITY, euro));
            Account bobAccount = bank.openAccount(new Account(
                UUID.randomUUID(), "Bob EUR", Optional.of(bob), AccountType.LIABILITY, euro));
            OperationMetadata deposit = metadata("Example funding");
            bank.deposit(aliceAccount.accountId(), new Money("100.00", euro), deposit);
            OperationMetadata transfer = metadata("Example transfer");
            JournalEntry entry = bank.transfer(aliceAccount.accountId(), bobAccount.accountId(),
                new Money("25.00", euro), transfer);
            System.out.println("N² Bank Core — library example");
            for (Posting posting : entry.postings()) {
                String name = posting.accountId().equals(aliceAccount.accountId()) ? "Alice" : "Bob";
                System.out.printf("%s %s %s %s%n", name, posting.direction(),
                    posting.amount().currency().getCurrencyCode(),
                    posting.amount().amount().toPlainString());
            }
        }
    }

    private static OperationMetadata metadata(String reference) {
        return new OperationMetadata(UUID.randomUUID(), Instant.now(), reference,
            IdempotencyKey.create());
    }
}
