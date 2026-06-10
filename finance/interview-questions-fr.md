# Questions d'entretien — Développeur Java en mission Banque/Finance

> Ce fichier reflète la réalité des entretiens pour des missions Java dans les banques et sociétés de gestion.
>
> **La répartition réelle :** ~70% questions Java/technique, ~30% compréhension du domaine finance.
> Les intervieweurs ne demandent PAS de pricer des options ou calculer des YTM à un dev Java.
> Ils veulent savoir : est-ce que tu comprends assez le domaine pour travailler dedans sans se perdre ?

---

## Les calculs en entretien Java finance — la réalité

**Est-ce qu'on te pose des questions de calcul ?** Oui, parfois. Mais voici la vérité sur leur niveau.

### Calculs SIMPLES — posés à un dev Java (niveau lycée/BTS)

Ces calculs testent juste que tu comprends le concept de base. Pas de formule complexe.

```
✅ Coupon d'une obligation
   "Bond de 1 000€, coupon 5% semestriel → combien tu reçois tous les 6 mois ?"
   → 1000 × 5% / 2 = 25€   [fin de l'exercice]

✅ Basis points
   "0,75% = combien de bps ?"
   → 75 bps   [5 secondes]

✅ Dividend yield
   "Action à 80€, dividende annuel 4€ → yield ?"
   → 4 / 80 × 100 = 5%   [mental]

✅ P/E ratio
   "Résultat 10M€, 5M actions, cours 40€ → EPS et P/E ?"
   → EPS = 2€,  P/E = 20x   [30 secondes]

✅ Future Value simple
   "5 000€ à 6% pendant 2 ans, composé annuellement → FV ?"
   → 5000 × (1.06)² = 5 618€   [calculatrice mentale ou papier]

✅ Present Value simple
   "1 000€ dans 3 ans, taux 8% → PV ?"
   → 1000 / (1.08)³ ≈ 793,83€   [avec calculatrice, c'est acceptable]
```

### Calculs que tu ne verras PAS en entretien Java dev

Ces calculs appartiennent aux entretiens quant / risk developer. On ne te les posera pas.

```
❌ Pricer un call option (Black-Scholes)
❌ Calculer un YTM (résolution numérique itérative)
❌ Calculer une VaR par Monte Carlo
❌ Calculer les flux nets d'un IRS complexe
❌ Calculer la duration de Macaulay
❌ Calculer un taux forward FX avec parité des taux d'intérêt
❌ Valoriser un MBS (Mortgage-Backed Security)
```

### Comment se préparer aux calculs

- Sois à l'aise avec les **formules de base** (PV, FV, coupon, yield)
- Sais faire les calculs **sur papier ou mentalement** pour les cas simples
- Si un calcul est complexe, demande si une calculatrice est autorisée — c'est normal
- L'objectif n'est pas le calcul en lui-même, c'est de montrer que tu comprends **ce que le chiffre représente**

> **Exemple typique d'échange en entretien :**
> Interviewer : "Si les taux montent, qu'est-ce qui se passe au prix d'une obligation ?"
> Bonne réponse : "Il baisse. Plus les taux de marché sont hauts, moins les coupons fixes existants sont attractifs — le prix s'ajuste à la baisse pour compenser."
> → Il ne va pas te demander de calculer le nouveau prix exact avec la formule.

---

---

## Menu

- [Partie 1 — Java & technique (ce qui compte le plus)](#partie-1--java--technique)
  - [Argent et précision numérique](#argent-et-précision-numérique)
  - [Design & architecture](#design--architecture)
  - [Concurrence et performance](#concurrence-et-performance)
  - [Messaging et événementiel](#messaging-et-événementiel)
  - [Persistance et transactionnel](#persistance-et-transactionnel)
  - [Spring Boot / framework](#spring-boot--framework)
  - [Tests](#tests)
- [Partie 2 — Compréhension du domaine finance](#partie-2--compréhension-du-domaine-finance)
  - [Vocabulaire et concepts de base](#vocabulaire-et-concepts-de-base)
  - [Structure et organisation](#structure-et-organisation)
  - [Systèmes et protocoles](#systèmes-et-protocoles)
- [Partie 3 — Questions comportementales](#partie-3--questions-comportementales)
- [Ce qu'on ne te demandera PAS (sauf rôle quant/risk)](#ce-quon-ne-te-demandera-pas)

---

## Partie 1 — Java & technique

> C'est ici que tu gagnes ou perds l'entretien. Le domaine finance s'apprend, Java non.

---

### Argent et précision numérique

---

**Q1. Pourquoi ne pas utiliser `double` pour représenter des montants d'argent ?**

C'est LA question Java finance par excellence. Elle revient dans presque tous les entretiens banque.

```java
// Démonstration du problème
double a = 0.1 + 0.2;
System.out.println(a); // 0.30000000000000004  ← pas acceptable en finance

// IEEE 754 ne peut pas représenter exactement 0.1 en binaire
// Sur des millions de transactions, l'erreur s'accumule
```

**Solution standard : `BigDecimal`**

```java
// Toujours instancier avec une String, jamais avec un double
BigDecimal a = new BigDecimal("0.10"); // ✅
BigDecimal b = new BigDecimal("0.20");
BigDecimal result = a.add(b); // = 0.30 exactement

BigDecimal bad = new BigDecimal(0.1); // ❌ hérite de l'imprécision du double
```

**Mode d'arrondi : `RoundingMode.HALF_EVEN` (Banker's Rounding)**

```java
BigDecimal result = montant.divide(taux, 10, RoundingMode.HALF_EVEN);
// HALF_EVEN : arrondit vers le chiffre pair quand exactement à mi-chemin
// 2.5 → 2,  3.5 → 4,  4.5 → 4,  5.5 → 6
// Réduit le biais cumulatif sur des millions de calculs
```

**Alternative pour les systèmes très performants :** stocker les montants en centimes (Long).
```java
long prixEnCentimes = 1099L; // représente 10,99€
// Pas de virgule flottante du tout, opérations ultra-rapides
// Attention à l'overflow et à la gestion des conversions d'affichage
```

> **En entretien** : si tu expliques `HALF_EVEN` et pourquoi, c'est un signal fort. La plupart des candidats disent juste "BigDecimal" sans aller plus loin.

---

**Q2. On te donne un taux annuel. Comment calculer les intérêts composés mensuels sur une position de 10 000€ à 6% sur 1 an ?**

Cette question teste si tu sais coder un calcul financier de base **proprement** en Java — pas si tu connais la formule par cœur.

```java
public BigDecimal calculateCompoundInterest(
        BigDecimal principal,
        BigDecimal annualRate,
        int periodsPerYear,
        int totalPeriods) {

    // FV = P × (1 + r/n)^(n×t)
    BigDecimal ratePerPeriod = annualRate
        .divide(BigDecimal.valueOf(periodsPerYear), 10, RoundingMode.HALF_EVEN);

    BigDecimal factor = BigDecimal.ONE.add(ratePerPeriod)
        .pow(totalPeriods); // pow() sur BigDecimal pour les entiers

    return principal.multiply(factor)
        .setScale(2, RoundingMode.HALF_EVEN);
}

// Utilisation
BigDecimal result = calculateCompoundInterest(
    new BigDecimal("10000"),
    new BigDecimal("0.06"),
    12,  // mensuel
    12   // 1 an = 12 mois
);
// Résultat : 10 616,78€
```

> **Ce qu'ils évaluent** : l'utilisation de `BigDecimal`, le choix du `RoundingMode`, la lisibilité du code. Pas la formule mathématique.

---

### Design & architecture

---

**Q3. Comment modélises-tu un trade en Java ?**

Question très fréquente. Elle révèle si tu comprends les contraintes du domaine.

```java
public class Trade {
    private final String tradeId;          // UUID, immuable — jamais null
    private final String isin;             // identifiant de l'instrument
    private final TradeDirection direction; // BUY ou SELL
    private final BigDecimal quantity;
    private final Money price;             // montant + devise
    private final String counterpartyLei;  // LEI de la contrepartie
    private final LocalDate tradeDate;
    private final LocalDate settlementDate;
    private TradeStatus status;
    private final Instant createdAt;
    private final String createdBy;
    @Version
    private Long version;                  // optimistic locking
}

public enum TradeDirection { BUY, SELL }

public enum TradeStatus {
    NEW, VALIDATED, CONFIRMED, PENDING_SETTLEMENT, SETTLED, CANCELLED, FAILED
}

// Value Object pour l'argent
public record Money(BigDecimal amount, Currency currency) {}
```

**Points importants à mentionner :**
- `tradeId` est immuable et unique — clé d'idempotence
- On ne supprime jamais un trade, on le passe à `CANCELLED`
- `version` pour l'optimistic locking — évite les mises à jour concurrentes
- `Money` est un Value Object typé — on n'additionne pas EUR et USD sans conversion explicite

---

**Q4. Comment implémentes-tu la machine à états d'un trade ?**

Le trade passe par des états bien définis. Les transitions incorrectes ne doivent pas être possibles.

```java
// Pattern State ou simple validation dans le service
public class TradeService {

    public void confirm(Trade trade) {
        if (trade.getStatus() != TradeStatus.VALIDATED) {
            throw new InvalidTradeTransitionException(
                "Cannot confirm a trade in status: " + trade.getStatus()
            );
        }
        trade.setStatus(TradeStatus.CONFIRMED);
        tradeRepository.save(trade);
        eventPublisher.publish(new TradeConfirmedEvent(trade.getTradeId()));
    }
}

// Alternative plus robuste : enum avec transitions autorisées
public enum TradeStatus {
    NEW(Set.of("VALIDATED", "CANCELLED")),
    VALIDATED(Set.of("CONFIRMED", "CANCELLED")),
    CONFIRMED(Set.of("PENDING_SETTLEMENT", "CANCELLED")),
    PENDING_SETTLEMENT(Set.of("SETTLED", "FAILED")),
    SETTLED(Set.of()),      // état terminal
    CANCELLED(Set.of()),    // état terminal
    FAILED(Set.of());       // état terminal

    private final Set<String> allowedNextStatuses;

    public boolean canTransitionTo(TradeStatus next) {
        return allowedNextStatuses.contains(next.name());
    }
}
```

---

**Q5. Qu'est-ce que l'Outbox Pattern ? Pourquoi l'utiliser en banque ?**

Problème : comment garantir qu'un trade bookké en base et l'événement Kafka publié sont **toujours cohérents** — même si le système crashe entre les deux ?

```java
// ❌ Problème : pas atomique — si crash entre save et publish, on perd l'event
tradeRepository.save(trade);
kafkaProducer.publish("trades", new TradeBookedEvent(trade)); // crash ici ?

// ✅ Solution : Outbox Pattern
// On écrit le trade ET l'event dans la même transaction DB
@Transactional
public Trade bookTrade(TradeBookingRequest request) {
    Trade trade = mapper.toTrade(request);
    tradeRepository.save(trade);

    // Dans la même transaction → atomique
    OutboxEvent event = new OutboxEvent(
        "TRADE_BOOKED",
        trade.getTradeId(),
        serialize(trade)
    );
    outboxRepository.save(event);

    return trade;
}

// Un worker séparé lit la table outbox et publie sur Kafka
// Il marque les events comme publiés après confirmation ACK
// Garantit at-least-once delivery sans distributed transaction (2PC)
```

> En banque, perdre un event = trade non notifié aux systèmes de risque ou de settlement. C'est un problème réel.

---

**Q6. Comment gères-tu la détection de doublons (idempotence) dans un service de booking ?**

En banque, recevoir deux fois le même ordre peut déclencher deux achats réels. L'idempotence est critique.

```java
// Stratégie 1 : contrainte unique sur l'identifiant externe
// Le client envoie un clientOrderId unique par ordre
@Column(unique = true)
private String clientOrderId;

// INSERT ... ON CONFLICT DO NOTHING  → pas d'erreur, pas de doublon

// Stratégie 2 : vérification applicative avant traitement
public TradeResponse bookTrade(TradeRequest request) {
    Optional<Trade> existing = tradeRepository
        .findByClientOrderId(request.getClientOrderId());

    if (existing.isPresent()) {
        log.warn("Duplicate trade request detected: {}", request.getClientOrderId());
        return TradeResponse.from(existing.get()); // renvoyer le résultat existant
    }
    // ... continuer le booking
}

// Stratégie 3 : cache Redis pour les requêtes récentes (hautes fréquences)
if (redisTemplate.hasKey("order:" + request.getClientOrderId())) {
    return cachedResponse;
}
```

---

**Q7. Tu dois lire 500 000 positions depuis la base et calculer la valeur de marché. Comment optimises-tu ?**

Question de performance / batch très fréquente en middle office / back office.

```java
// ❌ Mauvais : tout charger en mémoire
List<Position> all = positionRepository.findAll(); // OutOfMemoryError

// ✅ Spring Batch avec chunks
@Bean
public Step valuationStep() {
    return stepBuilderFactory.get("marketValuation")
        .<Position, ValuationResult>chunk(1000) // traite 1000 par 1000
        .reader(positionJpaPagingReader())       // lecture paginée
        .processor(marketValueProcessor())       // calcul valeur de marché
        .writer(valuationWriter())               // écriture en batch
        .taskExecutor(taskExecutor())            // parallélisation
        .throttleLimit(4)                        // 4 threads parallèles
        .build();
}

// ✅ Ou : Stream JPA (pas de chargement en mémoire)
@Query("SELECT p FROM Position p WHERE p.bookDate = :date")
@QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = "500"))
Stream<Position> streamByBookDate(@Param("date") LocalDate date);
```

**Points à mentionner :** pagination, streaming, chunk size, parallélisme contrôlé, monitoring de la durée vs cut-off time.

---

### Concurrence et performance

---

**Q8. Plusieurs threads accèdent en même temps au même compte pour mettre à jour son solde. Comment gères-tu ça ?**

```java
// ❌ Pas thread-safe
account.setBalance(account.getBalance().add(amount));

// ✅ Option 1 : Optimistic locking JPA (recommandé si peu de conflits)
@Entity
public class Account {
    @Version
    private Long version; // si deux threads lisent la même version, le 2ème échoue
}
// Spring lance une OptimisticLockException → retry ou erreur explicite

// ✅ Option 2 : SELECT FOR UPDATE (pessimistic locking)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Account findByIdForUpdate(@Param("id") Long id);

// ✅ Option 3 : UPDATE atomique en SQL
@Modifying
@Query("UPDATE Account a SET a.balance = a.balance + :amount WHERE a.id = :id")
int addToBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);
```

> En banque, on préfère généralement l'optimistic locking + retry, ou les updates SQL atomiques. Le pessimistic locking peut causer des deadlocks à grande échelle.

---

**Q9. C'est quoi le problème avec `synchronized` sur une méthode dans un service Spring ?**

```java
// ❌ Problème en environnement distribué (plusieurs instances du service)
@Service
public class PositionService {
    public synchronized void updatePosition(String tradeId) {
        // synchronized ne protège qu'au niveau d'une JVM
        // si 2 instances tournent (Kubernetes), le synchronized ne sert à rien
    }
}

// ✅ Solutions correctes :
// 1. Distributed lock (Redis SETNX, Redisson, Zookeeper)
// 2. Database-level locking (SELECT FOR UPDATE)
// 3. Design event-driven : chaque event traité séquentiellement par partition Kafka
//    (même clé de partitionnement = même thread → pas de concurrence)
```

---

### Messaging et événementiel

---

**Q10. Pourquoi utiliser Kafka plutôt qu'une API REST synchrone entre un système de booking et un système de risque ?**

```
REST synchrone :
  Book → [appel REST] → Risk System
  Si Risk System est down → booking échoue
  Si Risk System est lent → le trader attend
  Couplage fort

Kafka (asynchrone) :
  Book → [publish event] → Kafka Topic
                              ↓
                        Risk System consomme quand il peut
  Booking ne connaît pas Risk System
  Si Risk System est down → les events s'accumulent, seront traités au retour
  Découplage total
```

**En banque, Kafka est partout parce que :**
- Les systèmes front, middle, back office sont développés par des équipes différentes
- Un problème en back office ne doit pas bloquer le trading
- Les events peuvent être rejoués en cas d'incident
- On peut ajouter des consumers sans modifier le producer (reporting, audit, etc.)

---

**Q11. Un consumer Kafka tombe en panne et redémarre. Que se passe-t-il ? Comment éviter de traiter deux fois le même message ?**

```java
// Kafka garantit at-least-once delivery par défaut
// Si le consumer crash après traitement mais avant commit de l'offset → le message est rejoué

// ✅ Garantir l'idempotence côté consumer
@KafkaListener(topics = "trades")
public void onTradeBooked(TradeBookedEvent event) {
    // Vérifier si déjà traité avant de traiter
    if (processedEventRepository.existsById(event.getEventId())) {
        log.info("Event {} already processed, skipping", event.getEventId());
        return;
    }

    // Traiter ET marquer comme traité dans la même transaction
    processTradeInRisk(event);
    processedEventRepository.save(new ProcessedEvent(event.getEventId()));
}

// Auto-commit désactivé pour le contrôle manuel
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.listener.ack-mode=manual_immediate
```

---

### Persistance et transactionnel

---

**Q12. Quelle est la différence entre `@Transactional(propagation = REQUIRED)` et `REQUIRES_NEW)` ?**

```java
// REQUIRED (défaut) : rejoint la transaction existante si elle existe
// Si pas de transaction → en crée une nouvelle
// C'est le comportement standard pour 90% des cas

// REQUIRES_NEW : SUSPEND la transaction courante, crée une nouvelle
// La nouvelle transaction commit/rollback indépendamment
// Utile pour : logs d'audit (doivent persister même si la transaction principale rollback)

@Service
public class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logTradeAttempt(String tradeId, String user, String action) {
        // Cette écriture en base persiste même si le booking du trade échoue
        auditRepository.save(new AuditLog(tradeId, user, action, Instant.now()));
    }
}

// En banque : les logs d'audit et les traces réglementaires
// ne doivent JAMAIS être perdus même en cas d'erreur applicative
```

---

**Q13. Tu as une `LazyInitializationException` en prod sur une entité JPA. C'est quoi le problème et comment tu le règles ?**

```java
// Le problème : on accède à une collection lazy en dehors d'une session Hibernate
@Entity
public class Trade {
    @OneToMany(fetch = FetchType.LAZY)  // lazy par défaut
    private List<TradeEvent> events;
}

// Service
public TradeDTO getTradeWithEvents(String id) {
    Trade trade = tradeRepository.findById(id).orElseThrow();
    return mapper.toDTO(trade); // ← ici Hibernate session fermée → BOOM
    // mapper accède à trade.getEvents() → LazyInitializationException
}

// ✅ Solutions :
// 1. JOIN FETCH dans la query JPQL
@Query("SELECT t FROM Trade t JOIN FETCH t.events WHERE t.tradeId = :id")
Optional<Trade> findWithEvents(@Param("id") String id);

// 2. EntityGraph
@EntityGraph(attributePaths = {"events"})
Optional<Trade> findById(String id);

// 3. Projection DTO directement depuis la query (évite de charger l'entité entière)
// 4. @Transactional sur la méthode service (étend la session)
```

---

### Spring Boot / framework

---

**Q14. Comment exposes-tu un batch de calcul de PnL qui doit tourner chaque soir à 18h ?**

```java
// Option 1 : Spring Batch + Spring Scheduler
@Component
public class PnlBatchScheduler {

    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Europe/Paris")
    public void launchPnlBatch() {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("date", LocalDate.now())
            .addLong("timestamp", System.currentTimeMillis()) // garantit unicité
            .toJobParameters();

        try {
            jobLauncher.run(pnlCalculationJob, params);
        } catch (JobExecutionException e) {
            log.error("PnL batch failed", e);
            alertingService.sendAlert("PnL batch failure", e.getMessage());
        }
    }
}

// Option 2 : déclenchement externe (Kubernetes CronJob, Jenkins, Control-M)
// Le batch expose un endpoint ou est déclenché par un message
// Plus flexible en production bancaire — les batchs sont souvent orchestrés par Control-M
```

> **À mentionner** : en banque, les batchs de nuit ont des cut-off times durs. Un batch PnL qui rate peut bloquer le reporting du lendemain matin. La surveillance et les alertes sont critiques.

---

**Q15. Comment gères-tu les secrets (mots de passe DB, clés API Bloomberg) dans une app Spring Boot en banque ?**

```java
// ❌ Jamais en dur dans le code ou dans application.properties committé
spring.datasource.password=mysecretpassword  // ← INTERDIT

// ✅ Options selon l'environnement bancaire

// 1. Variables d'environnement (le minimum)
spring.datasource.password=${DB_PASSWORD}

// 2. HashiCorp Vault (très répandu en banque)
spring.cloud.vault.uri=https://vault.internal.bank.com
spring.cloud.vault.authentication=KUBERNETES

// 3. AWS Secrets Manager / Azure Key Vault (cloud)

// 4. Kubernetes Secrets (monté en variable d'env ou volume)

// En banque : les équipes sécu imposent souvent une rotation automatique
// des credentials → ton app doit gérer le rechargement sans redémarrage
@RefreshScope
@ConfigurationProperties("datasource")
public class DataSourceConfig { ... }
```

---

### Tests

---

**Q16. Comment testes-tu un service de calcul de PnL sans toucher la base de données ?**

```java
// Test unitaire avec Mockito
@ExtendWith(MockitoExtension.class)
class PnlCalculationServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private MarketDataService marketDataService;

    @InjectMocks
    private PnlCalculationService pnlService;

    @Test
    void shouldCalculatePnlCorrectly() {
        // Given
        Position position = Position.builder()
            .isin("FR0000131104")
            .quantity(new BigDecimal("1000"))
            .bookPrice(new BigDecimal("98.50"))
            .build();

        when(positionRepository.findByDate(LocalDate.now()))
            .thenReturn(List.of(position));
        when(marketDataService.getPrice("FR0000131104"))
            .thenReturn(new BigDecimal("99.20"));

        // When
        PnlResult result = pnlService.calculateDailyPnl(LocalDate.now());

        // Then
        assertThat(result.getTotalPnl())
            .isEqualByComparingTo(new BigDecimal("700.00")); // (99.20-98.50) × 1000
    }
}
```

> **Attention** : en banque on discute souvent si mocker la DB est pertinent (une erreur de mock peut masquer un problème réel). Pour les calculs purs → mocks OK. Pour les requêtes critiques → test d'intégration avec H2 ou Testcontainers.

---

**Q17. C'est quoi Testcontainers et pourquoi l'utiliser pour des tests en contexte bancaire ?**

```java
// Testcontainers : démarre une vraie DB (Docker) pour tes tests d'intégration
// Au lieu d'un H2 en mémoire qui ne se comporte pas exactement comme Oracle/Postgres

@SpringBootTest
@Testcontainers
class TradeRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("trades_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }

    @Test
    void shouldPersistAndRetrieveTradeWithEvents() {
        // Test contre une vraie Postgres — les comportements de locking,
        // les contraintes, les types sont identiques à la prod
    }
}
```

---

## Partie 2 — Compréhension du domaine finance

> Ces questions te qualifient pour travailler **dans** le domaine. Pas besoin de calculs — comprendre les concepts suffit.

---

### Vocabulaire et concepts de base

---

**Q18. C'est quoi un trade ? Quels sont les champs essentiels ?**

Un trade est un accord entre deux parties pour échanger un actif financier (action, obligation, devise...) à un prix donné, à une date donnée.

Champs essentiels :
- `tradeId` — identifiant unique
- `instrument` / `isin` — ce qu'on achète/vend
- `direction` — BUY ou SELL
- `quantity` — combien
- `price` — à quel prix (avec devise)
- `counterparty` — avec qui (LEI)
- `tradeDate` — quand l'accord est conclu
- `settlementDate` — quand l'échange réel a lieu (souvent T+2)
- `status` — état dans le cycle de vie

---

**Q19. C'est quoi le settlement ? Et T+2 ?**

Le settlement (règlement-livraison) est l'échange effectif : les titres vont chez l'acheteur, le cash va chez le vendeur.

- **T+2** = 2 jours ouvrés après la date du trade
  - Trade le lundi → settlement le mercredi
- Standard actuel pour les actions en Europe
- Les US Treasuries sont passés à T+1 — tendance générale vers T+1

Avant le settlement, le trade est en attente (PENDING_SETTLEMENT). L'acheteur n'est pas encore propriétaire des titres.

---

**Q20. C'est quoi une position ?**

Une position = la somme nette de tous les trades sur un instrument.

```
Exemple :
- Matin : achat 1 000 actions BNP
- Midi : achat 500 actions BNP
- Après-midi : vente 300 actions BNP

Position nette = +1 200 actions BNP (long)
```

La position change en temps réel au fil des trades. Les systèmes de gestion de risque calculent la valeur de marché (mark-to-market) des positions.

---

**Q21. C'est quoi un bond (obligation) en deux phrases ?**

Un bond est un titre de dette : l'émetteur emprunte de l'argent aux investisseurs et s'engage à payer des intérêts périodiques (le coupon) et à rembourser le capital à l'échéance (maturity).

Exemple : l'État français émet une OAT 10 ans à 3% → tu prêtes 1 000€, tu reçois 30€/an pendant 10 ans, puis 1 000€ au bout de 10 ans.

---

**Q22. C'est quoi un swap ? (niveau dev Java)**

Un swap = deux parties échangent des flux financiers selon des règles définies à l'avance, sur un montant de référence (notionnel).

L'exemple le plus courant : **Interest Rate Swap (IRS)** — l'une paie un taux fixe, l'autre un taux variable. Seul le différentiel est échangé.

Pourquoi c'est important pour toi dev Java ? Parce que les swaps sont des instruments OTC avec un cycle de vie complexe : confirmation, valorisation quotidienne (MTM), échanges de collatéral, reporting EMIR. Tu pourrais avoir à coder n'importe laquelle de ces étapes.

---

**Q23. C'est quoi le mark-to-market (MTM) ?**

C'est la valorisation d'une position au prix de marché du jour — pas au prix d'achat historique.

Si tu as acheté 1 000 actions à 50€ et qu'elles valent 48€ aujourd'hui :
```
Valeur MTM   = 1 000 × 48€ = 48 000€
PnL du jour  = (48€ - 50€) × 1 000 = -2 000€  (perte latente)
```

Les systèmes calculent le MTM en fin de journée (EOD) — c'est le batchs PnL que les devs Java construisent.

---

**Q24. C'est quoi la différence entre un prix clean et un prix dirty pour une obligation ?**

- **Clean price** : prix affiché sur les écrans, hors intérêts courus
- **Dirty price** : prix réellement payé = clean price + intérêts courus depuis le dernier coupon

Les intérêts s'accumulent chaque jour entre deux dates de coupon. Quand tu achètes une obligation entre deux coupons, tu paies au vendeur les intérêts accumulés depuis le dernier coupon.

```
Dirty price = Clean price + Accrued interest
```

> **Pourquoi un dev Java doit le savoir ?** Parce que selon le contexte (affichage, calcul PnL, comptabilité), tu utiliseras l'un ou l'autre — et si tu les confonds, tes calculs seront faux.

---

**Q25. C'est quoi le PnL ? Comment il se calcule ?**

PnL = Profit and Loss. La variation de valeur d'un portefeuille sur une période.

```
PnL quotidien = Valeur MTM aujourd'hui - Valeur MTM hier

Exemple :
  Position hier : 1 000 actions à 50€ = 50 000€
  Position aujourd'hui : 1 000 actions à 52€ = 52 000€
  PnL du jour = +2 000€
```

En banque, le PnL est calculé chaque soir (batch EOD) et présenté aux traders le lendemain matin. Les devs Java construisent ces moteurs de calcul.

---

### Structure et organisation

---

**Q26. Quelle est la différence entre front office, middle office et back office ?**

| | Front Office | Middle Office | Back Office |
|---|---|---|---|
| Rôle | Génère le revenu | Contrôle, risque | Opérations |
| Qui | Traders, sales | Risk managers, quants | Settlement, comptabilité |
| Systèmes | Murex, OMS, pricing | Moteurs risque, PnL | Batch, SWIFT, réconciliation |
| Pour le dev Java | Latence faible | Calcul intensif | Fiabilité, auditabilité |

> En entretien, demande toujours sur quelle partie de la chaîne tu seras. Les contraintes sont très différentes.

---

**Q27. C'est quoi la différence entre buy side et sell side ?**

- **Sell side** (BNP Paribas, SocGen, Natixis...) : les banques qui créent et vendent des produits financiers, tiennent des marchés, exécutent des ordres pour leurs clients
- **Buy side** (Amundi, Carmignac, AXA IM...) : les sociétés de gestion qui investissent l'argent de leurs clients (fonds, assurance vie, retraites...)

**Pour toi dev Java** : le sell side a tendance à avoir des systèmes plus complexes (toute la chaîne booking-clearing-settlement), le buy side est plus orienté gestion de portefeuille et performance.

---

### Systèmes et protocoles

---

**Q28. C'est quoi le protocole FIX ?**

FIX (Financial Information eXchange) est le protocole de messagerie électronique standard pour communiquer des ordres entre acteurs financiers (OMS → broker, broker → bourse...).

Format tag=valeur :
```
35=D   → type de message : New Order Single (nouvel ordre)
55=AIR → Symbol (l'instrument)
54=1   → Side : 1=Buy, 2=Sell
38=500 → Quantity
40=2   → Type : Limit order
44=72.50 → Prix
```

Librairie Java : **QuickFIX/J**

Même si tu ne l'as jamais utilisé, savoir que ça existe et à quoi ça sert est attendu en entretien pour une mission trading/OMS.

---

**Q29. C'est quoi SWIFT ? Pourquoi c'est important pour un dev back office ?**

SWIFT est le réseau de messagerie interbancaire mondial — les banques s'envoient des instructions de paiement et de transfert de titres via SWIFT.

Messages courants :
- **MT103** : virement client
- **MT202** : transfert banque à banque
- **MT54x** : instructions de règlement-livraison de titres

En migration vers **ISO 20022** (format MX) — plus riche en données, JSON-like.

**Pour un dev Java back office** : tu auras probablement à générer ou parser des messages SWIFT (MT → MX), souvent via des librairies comme SWIFT SDK ou des solutions maison.

---

**Q30. C'est quoi Murex ? Tu n'y as jamais touché — que dis-tu en entretien ?**

Murex est la plateforme front-to-back la plus répandue dans les banques françaises (BNP Paribas, SocGen, Natixis, Crédit Agricole CIB). Elle gère le booking, le pricing, le risque, le PnL, le settlement.

**Si tu n'as pas d'expérience Murex :**

> "Je n'ai pas travaillé directement sur Murex, mais je comprends son rôle : c'est la plateforme centrale qui gère le cycle de vie des trades. En pratique, les développements autour de Murex impliquent souvent Java pour des intégrations via des files de messages (MQ, Kafka), des plugins, ou des systèmes adjacents. Ma valeur sera dans mes fondamentaux Java et ma compréhension du domaine — je suis à l'aise pour monter en compétence sur l'outil."

> Ne dis pas "je ne connais pas Murex donc je ne suis pas le bon profil". Les banques cherchent des devs Java solides, pas uniquement des experts Murex.

---

## Partie 3 — Questions comportementales

---

**Q31. Tu trouves un bug dans le calcul de PnL qui tourne en prod depuis 3 mois. Que fais-tu ?**

1. **Évaluer l'impact** : quelles positions ? Quelle amplitude ? Est-ce matériel (significatif) ?
2. **Ne pas corriger silencieusement** : escalader immédiatement au TL et au business. En banque, les régulateurs peuvent devoir être notifiés.
3. **Ne pas modifier les données historiques sans processus formel** : une écriture comptable corrective est le bon chemin, pas un UPDATE direct en base.
4. **Quantifier et documenter** : liste des enregistrements affectés, montant de l'écart.
5. **Corriger le code + ajouter des tests de non-régression**.
6. **Post-mortem** : comment ce bug est passé ? Quels tests manquaient ?

---

**Q32. Le déploiement en prod d'une release critique est prévu vendredi soir. Les tests échouent sur un point non bloquant. Que fais-tu ?**

Réponse attendue : **ne pas décider seul**. Présenter clairement au product owner et TL :
- Ce qui échoue exactement
- Si c'est bloquant pour les fonctionnalités critiques ou non
- Les risques de déployer vs de reporter

En banque, un mauvais déploiement vendredi soir avec une équipe réduite le week-end peut avoir des conséquences graves. La décision de go/no-go appartient au business, pas au développeur.

---

**Q33. On te donne un legacy code Java 8 sans tests, avec de la logique financière critique. Par où tu commences ?**

1. **Comprendre avant de modifier** : lire, tracer, comprendre ce que le code fait réellement
2. **Ne pas refactoriser à l'aveugle** — le code legacy en banque a souvent des règles métier implicites non documentées
3. **Golden master testing** : capturer les outputs actuels pour les utiliser comme référence de non-régression
4. **Ajouter des tests sur le comportement existant** avant toute modification
5. **Modifier par petites étapes**, une à la fois, en validant à chaque étape

> En banque, "ne pas casser ce qui marche" est souvent plus important que "écrire du beau code".

---

## Ce qu'on ne te demandera PAS

> Sauf si le rôle est explicitement quant, risk developer, ou développeur de moteur de pricing.

- ❌ Pricer une option avec Black-Scholes
- ❌ Calculer un YTM (Yield to Maturity)
- ❌ Calculer une VaR par simulation Monte Carlo
- ❌ Calculer les flux d'un IRS à la main
- ❌ Calculer un taux forward FX
- ❌ Expliquer les hypothèses du modèle de Gordon
- ❌ Calculer la duration d'une obligation
- ❌ Analyser une yield curve inversée en détail

**Ces questions appartiennent aux entretiens pour :**
- Quant developer / Quant analyst
- Risk developer (moteurs VaR, Greeks)
- Structureur / Ingénieur financier

**Pour un dev Java en mission banque classique**, les questions finance se limitent au vocabulaire de base, à la compréhension du cycle de vie des trades, et à savoir où tu vas travailler dans la chaîne.
