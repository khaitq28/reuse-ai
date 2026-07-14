# Round 2 & 3 — Questions Métier Java + Finance
## Corrigé détaillé pour développeur Java (Front Office / Trading / Banque)

> **Contexte** : Réponses aux questions d'entretien Round 2 et Round 3 pour des missions Java en banque.
> Les réponses sont formulées du point de vue d'un développeur Java comprenant le domaine finance.

---

## Table des matières

### Round 2 — Questions Métier
- [Q1. Que signifie "hedger" son risque de taux ?](#q1-que-signifie-hedger-son-risque-de-taux-)
- [Q2. Payment Frequency vs Reset Frequency dans un IRS](#q2-payment-frequency-vs-reset-frequency-dans-un-irs)
- [Q3. Settlement échoué — causes métier](#q3-settlement-échoué--causes-métier)
- [Q4. Risk Management préfère la Position aux Trades](#q4-risk-management-préfère-la-position-aux-trades)
- [Q5. Euribor publié — quels systèmes sont impactés ?](#q5-euribor-publié--quels-systèmes-sont-impactés-)
- [Q6. Rôle de Trade Capture, Pricing Engine, Settlement Engine](#q6-rôle-de-trade-capture-pricing-engine-settlement-engine)
- [Q7. Trade non confirmé — autoriser le settlement ?](#q7-trade-non-confirmé--autoriser-le-settlement-)
- [Q8. Modification d'un IRS — qui est autorisé ?](#q8-modification-dun-irs--qui-est-autorisé-)
- [Q9. Architecture microservices — découpage par domaine](#q9-architecture-microservices--découpage-par-domaine)
- [Q10. PnL en baisse de 2M€ — que vérifier en premier ?](#q10-pnl-en-baisse-de-2m--que-vérifier-en-premier-)

### Round 3 — Java Design + Finance
- [Q11. Settlement batch interrompu — éviter le double settlement](#q11-settlement-batch-interrompu--éviter-le-double-settlement)
- [Q12. Valoriser 2 millions d'IRS chaque nuit](#q12-valoriser-2-millions-dirs-chaque-nuit)
- [Q13. Nouvelle courbe Euribor — recalcul total ou partiel ?](#q13-nouvelle-courbe-euribor--recalcul-total-ou-partiel-)
- [Q14. Concurrence entre saisie de trade et mise à jour de position](#q14-concurrence-entre-saisie-de-trade-et-mise-à-jour-de-position)
- [Q15. Message FpML reçu deux fois — éviter les doublons](#q15-message-fpml-reçu-deux-fois--éviter-les-doublons)

### Questions Complémentaires
- [Q16a. Différence entre Order, Trade et Position](#q16a-différence-entre-order-trade-et-position)
- [Q16b. Cycle de vie d'un IRS](#q16b-cycle-de-vie-dun-irs)
- [Q16c. Rôle de la confirmation d'un trade OTC](#q16c-rôle-de-la-confirmation-dun-trade-otc)
- [Q16d. Garantir qu'un settlement ne soit jamais exécuté deux fois](#q16d-garantir-quun-settlement-ne-soit-jamais-exécuté-deux-fois)
- [Q16e. Recalcul de PnL quand les Market Data changent](#q16e-recalcul-de-pnl-quand-les-market-data-changent)

---

## Round 2 — Questions Métier (Java + Finance)

---

### Q1. Que signifie "hedger" son risque de taux ?

#### Que signifie hedger ?

**Hedger** (se couvrir) signifie prendre une position financière opposée à une exposition existante pour réduire ou éliminer un risque.

Analogie simple : si tu as une maison (risque incendie), tu souscris une assurance (couverture). En finance, c'est pareil : si tu as une exposition à un risque de taux, tu prends un instrument qui se comporte à l'inverse.

**Dans le contexte de la question :**

Le client a un **prêt à taux variable** (ex : EURIBOR 3M + 1,5%). Si l'EURIBOR monte de 2% à 4%, ses mensualités augmentent fortement. Il subit un **risque de taux à la hausse**.

Pour se couvrir, il conclut un **Interest Rate Swap (IRS)** :
- Il **paie un taux fixe** (ex : 3%) à la banque
- La banque **lui paie l'EURIBOR variable**

Les deux paiements variables se compensent. Le client ne paie effectivement que le taux fixe de 3% — il a **transformé synthétiquement son emprunt variable en emprunt fixe** sans changer le contrat de prêt.

```
Situation initiale :
  Client  → [EURIBOR + 1,5%]  → Banque prêteuse

Après hedge IRS :
  Client  → [Fixe 3%]          → Contrepartie swap
  Client  ← [EURIBOR]          ← Contrepartie swap
  Client  → [EURIBOR + 1,5%]  → Banque prêteuse

Net pour le client :
  Paie fixe 3% + 1,5% = 4,5% fixe, peu importe où va l'EURIBOR
```

#### Pourquoi ne pas garder simplement le taux variable ?

| Raison | Explication |
|--------|-------------|
| **Risque budgétaire** | Une entreprise ne peut pas laisser ses charges financières fluctuer. Elle doit budgétiser ses coûts à l'avance. |
| **Risque de solvabilité** | Si les taux montent brutalement (+3% en un an comme en 2022-2023), les charges explosent. |
| **Obligations contractuelles** | Certains covenants de prêt exigent une couverture du risque de taux. |
| **Reporting IFRS** | Les institutions ont des obligations (IFRS 9) sur la gestion des risques de taux. |

> **En pratique** : la plupart des grandes entreprises et banques couvrent systématiquement leurs emprunts via des IRS. Garder un taux purement variable sur longue durée est une prise de risque délibérée, pas une position par défaut.

---

### Q2. Payment Frequency vs Reset Frequency dans un IRS

#### Rappel des champs d'un IRS

```
Trade Date        : date de conclusion du contrat
Effective Date    : date de début du calcul des intérêts
Maturity Date     : date de fin du contrat
Payment Frequency : fréquence d'échange des flux d'intérêts
Reset Frequency   : fréquence de mise à jour du taux variable
Notional          : montant de référence (ex : 100M€) — jamais échangé
Fixed Rate        : taux fixe payé/reçu
Floating Index    : indice variable (EURIBOR 3M, SOFR...)
```

#### À quoi sert Payment Frequency ?

La **Payment Frequency** définit à quelle fréquence les flux d'intérêts sont **effectivement échangés** entre les deux parties.

Exemples courants : `QUARTERLY` (trimestriel), `SEMI_ANNUAL` (semestriel), `ANNUAL` (annuel).

À chaque date de paiement, on calcule ce que chaque partie doit à l'autre et on échange le **net** (la différence).

#### À quoi sert Reset Frequency ?

La **Reset Frequency** définit à quelle fréquence le **taux variable est observé et fixé** pour la prochaine période de calcul.

C'est indépendant du paiement : l'EURIBOR peut être "fixé" tous les mois, mais le paiement n'avoir lieu que tous les trimestres.

#### Peuvent-elles être différentes ?

**Oui, absolument.** C'est courant dans certaines structures.

**Exemple typique :**
```
Reset Frequency   : mensuel  (on lit l'EURIBOR 1M chaque mois)
Payment Frequency : trimestriel (on paie tous les 3 mois)

→ Le taux est fixé chaque mois,
  les 3 montants mensuels sont accumulés
  et payés en une seule fois à la date de paiement trimestrielle.
```

**Implications pour un développeur Java :**

```java
public class IrsSchedule {
    private final Frequency paymentFrequency;  // ex : QUARTERLY
    private final Frequency resetFrequency;     // ex : MONTHLY

    // Les deux calendriers sont calculés SÉPARÉMENT
    public List<LocalDate> generatePaymentDates(LocalDate start, LocalDate end) {
        // Toutes les 3 mois
    }

    public List<LocalDate> generateResetDates(LocalDate start, LocalDate end) {
        // Tous les mois → le moteur lit l'EURIBOR à chaque date de reset
    }
}
```

Les données de marché (fixings EURIBOR) doivent être récupérées à chaque date de reset, pas seulement aux dates de paiement.

---

### Q3. Settlement échoué — causes métier

> Un trader vous appelle : "Le settlement de ce trade a échoué."
> Sans parler d'un bug Java, quelles peuvent être les causes métier ?

#### Catégorie 1 : Problème de titres (Fail to Deliver)

- L'**acheteur ne reçoit pas les titres** car le vendeur ne les a pas livrés à temps
- Le vendeur a une **position insuffisante** : il a vendu des titres qu'il n'avait pas en compte
- Les titres sont **bloqués en collatéral** chez une autre contrepartie ou CCP

#### Catégorie 2 : Problème de cash

- L'**acheteur n'a pas les fonds suffisants** sur son compte de règlement (Nostro)
- Le **virement a été rejeté** (problème IBAN, BIC, motif AML)
- **Délai de funding** : le cash arrive après la deadline de settlement

#### Catégorie 3 : Mismatch des instructions

Les instructions de settlement de l'acheteur et du vendeur ne concordent pas :
- ISIN différent
- Quantité différente
- Compte de règlement incorrect
- Mauvaise place de settlement (Euroclear vs Clearstream)
- La contrepartie n'a pas envoyé ses instructions dans les délais

#### Catégorie 4 : Trade non confirmé

- Le trade n'est **pas encore confirmé** par les deux parties → le settlement ne peut pas être initié
- Les termes en "broke" (non réconciliés entre contreparties)

#### Catégorie 5 : Problème réglementaire

- La contrepartie est sur une **liste de sanctions** (OFAC, UE) → paiement gelé
- **Restrictions d'embargo** sur la devise ou le pays
- **Problème KYC/AML** : la contrepartie n'a pas fourni les documents requis

#### Catégorie 6 : Opérationnel de marché

- **Ferie bancaire** dans le pays de settlement → date tombant un jour non ouvré
- **Cut-off time dépassé** : instructions arrivées après la limite d'acceptation du dépositaire
- **Congestion de la CCP ou du dépositaire** en période de forte activité

#### Résumé visuel

```
Settlement failure
├── Titres          → Fail to deliver, titres bloqués en collatéral
├── Cash            → Fonds insuffisants, virement rejeté (AML)
├── Instructions    → Mismatch ISIN/quantité/compte entre les deux parties
├── Confirmation    → Trade non confirmé, termes en broke
├── Réglementaire   → Sanctions, embargo, KYC/AML
└── Opérationnel    → Ferie, cut-off time dépassé
```

> **En entretien** : montrer ces catégories (pas seulement "le code a planté") prouve que tu comprends l'environnement opérationnel.

---

### Q4. Risk Management préfère la Position aux Trades

#### La réponse courte

Un trade est un **événement ponctuel passé**. Une position est l'**état actuel de l'exposition au marché**. Pour gérer le risque, ce qui compte c'est l'exposition nette actuelle, pas l'historique des transactions.

#### Explication détaillée

```
Trade 1 : Achat 1 000 actions BNP à 58€
Trade 2 : Achat 500 actions BNP à 60€
Trade 3 : Vente 1 200 actions BNP à 62€
```

Ces trois trades ont eu lieu. Mais quelle est l'exposition actuelle sur BNP ?

```
Position nette = +1 000 + 500 - 1 200 = +300 actions BNP
Valeur MTM     = 300 × 62€ = 18 600€
```

C'est **cette valeur** que le Risk Manager surveille, pas les 3 trades individuels.

#### Pourquoi c'est critique pour le Risk Management

| Ce que le Risk Manager calcule | Pourquoi la Position, pas les Trades |
|-------------------------------|--------------------------------------|
| **VaR** (perte maximale probable) | Combien peut-on perdre sur notre position nette ? |
| **DV01** (sensibilité aux taux) | De combien varie ma position si les taux bougent de 1bp ? |
| **Limite de position** | Le trader dépasse-t-il sa limite autorisée en net ? |
| **Mark-to-Market** | Quelle est la valeur de marché totale aujourd'hui ? |
| **Concentration risk** | Est-on trop concentré sur un seul émetteur ? |

Si le Risk Engine regardait les trades un par un, il devrait les agréger à chaque fois. La **position est l'agrégation déjà calculée**, prête à l'emploi.

#### Conséquences pour l'architecture Java

```java
// Mauvais : calculer le risque trade par trade (coûteux)
List<Trade> trades = tradeRepository.findAll();
BigDecimal totalRisk = trades.stream()
    .map(this::calculateRiskPerTrade)
    .reduce(BigDecimal.ZERO, BigDecimal::add);

// Bon : lire directement la position agrégée (temps réel)
Position position = positionRepository.findByInstrumentAndBook(isin, book);
BigDecimal risk = riskCalculator.calculateVar(position);
// La position est tenue à jour à chaque nouveau trade bookké
```

---

### Q5. Euribor publié — quels systèmes sont impactés ?

L'EURIBOR est publié chaque matin vers 11h00 CET par l'EMMI. C'est un **événement déclencheur** pour plusieurs systèmes.

#### OMS (Order Management System)

**Impact : Indirect, léger**

L'OMS gère les ordres de trading. L'EURIBOR n'impacte pas directement la saisie d'ordres sur actions. Cependant, les traders peuvent réagir à la publication (nouveau fixing plus haut → ordres de vente d'obligations), et certains ordres sur swaps ou FRN utilisent l'EURIBOR comme paramètre.

#### Pricing Engine

**Impact : Direct et immédiat**

C'est le système **le plus directement impacté**. L'EURIBOR est une donnée de marché fondamentale pour valoriser :

- **IRS** : la jambe variable est basée sur l'EURIBOR → le prochain cash flow change
- **FRN (Floating Rate Notes)** : coupon = EURIBOR + spread → le prochain coupon est recalculé
- **Caps, Floors, Swaptions** : instruments dérivés dont la valeur dépend du niveau de l'EURIBOR
- **Courbe de taux** : l'EURIBOR alimente la courbe court terme (3M, 6M...) pour actualiser tous les flux futurs

```java
@EventListener
public void onEuriborFixing(EuriborFixingEvent event) {
    marketDataRepository.save(new MarketData(
        "EURIBOR_3M",
        event.getFixingDate(),
        event.getRate()
    ));
    // Déclencher le repricing des positions sensibles à cette courbe
    pricingEngine.repriceAffectedPositions("EURIBOR_3M");
}
```

#### Risk Engine

**Impact : Direct, important**

Le Risk Engine recalcule les métriques de risque dès que les données de marché changent :

- **DV01 / BPV** : la sensibilité aux taux change avec le nouveau point de courbe
- **VaR** : les simulations utilisent les taux actuels
- **PnL Explain** : on décompose le PnL — la variation due à l'EURIBOR est isolée
- **Positions limites** : si l'EURIBOR monte fortement, la valeur MTM des positions long obligations baisse, potentiellement dépassant des limites de perte

#### Settlement Engine

**Impact : Indirect, ciblé**

Le Settlement Engine est impacté **uniquement pour les IRS et FRN dont la date de reset est aujourd'hui** :

- L'EURIBOR publié est le **taux de référence pour calculer le prochain cash flow variable**
- Si la date de paiement est proche, le montant est maintenant calculable et l'instruction SWIFT peut être générée

```java
public BigDecimal calculateFloatingCashFlow(IrsTrade irs, BigDecimal euriborRate) {
    BigDecimal dayCountFraction = calculateDCF(irs.getPeriodStart(), irs.getPeriodEnd());
    return irs.getNotional()
              .multiply(euriborRate)
              .multiply(dayCountFraction)
              .setScale(2, RoundingMode.HALF_EVEN);
}
```

#### Résumé

| Système | Impact | Raison |
|---------|--------|--------|
| **OMS** | Indirect | L'OMS lui-même ne consomme pas l'EURIBOR directement |
| **Pricing Engine** | **Direct et immédiat** | Repricing de tous les IRS, FRN, mise à jour de la courbe |
| **Risk Engine** | **Direct** | Recalcul DV01, VaR, PnL Explain |
| **Settlement Engine** | Indirect (ciblé) | Seulement les trades avec reset date = aujourd'hui |

---

### Q6. Rôle de Trade Capture, Pricing Engine, Settlement Engine

#### Trade Capture

> Enregistre les trades conclus par les traders et les saisit dans le système central (booking).

C'est le **point d'entrée** de tout trade dans le système :
- Reçoit les trades (via FIX, FpML, interface utilisateur)
- Valide le format et les données (instrument connu, contrepartie valide, dates cohérentes)
- Génère un TradeId unique et immuable
- Persiste le trade en base (jamais de suppression — annulation avec trace)
- Publie un événement (Kafka/MQ) pour notifier les systèmes downstream

#### Pricing Engine

> Calcule la valeur de marché (Mark-to-Market) des positions et des trades à partir des données de marché.

- Reçoit les instruments (IRS, obligations, options...) et les données de marché (courbes de taux, prix spot)
- Applique les formules de valorisation appropriées au type d'instrument
- Produit le NPV (Net Present Value), les sensibilités (DV01, Greeks), le MTM
- Alimente le Risk Engine et le calcul PnL

#### Settlement Engine

> Orchestre l'échange effectif des titres et du cash entre les contreparties à la date de settlement.

- Identifie les trades dont la settlement date est aujourd'hui et le statut est CONFIRMED
- Vérifie la présence des instructions de settlement des deux parties
- Génère les messages SWIFT (MT541 pour les titres, MT103/202 pour le cash)
- Envoie aux dépositaires (Euroclear, Clearstream) et aux banques correspondantes
- Suit le statut de chaque settlement et gère les fails (alertes Middle Office)

---

### Q7. Trade non confirmé — autoriser le settlement ?

> Un trader vous dit : "Ce trade n'est pas encore confirmé." Autoriseriez-vous le Settlement ?

**Non. Jamais.**

#### Pourquoi la confirmation est un pré-requis absolu

La **confirmation** est le processus par lequel les deux contreparties valident que leurs enregistrements du trade sont identiques. C'est une vérification de **double accord**.

Si le trade n'est pas confirmé, il existe un risque que :
- Les termes soient différents entre les deux parties (mauvaise quantité, mauvais prix, mauvais instrument)
- L'une des parties ne reconnaisse pas le trade
- On settle un trade avec des termes erronés → perte financière directe

#### Exemple concret d'un settlement sans confirmation

```
Trader A (BNP)      : "J'ai acheté 1 000 obligations à 98,50"
Trader B (Goldman)  : "J'ai vendu  500 obligations à 98,50"  ← quantité différente !

Si on settle sans confirmer :
→ BNP reçoit 500 obligations mais paie pour 1 000
→ Litige post-settlement, réclamation auprès de Goldman
→ Perte sur le delta de 500 obligations
→ Pénalités CSDR (règlement européen sur les fails de settlement)
```

#### La règle métier standard

```java
public class SettlementEngine {

    public void initiateSettlement(Trade trade) {
        if (trade.getStatus() != TradeStatus.CONFIRMED) {
            throw new SettlementNotAllowedException(
                "Trade " + trade.getTradeId() +
                " cannot be settled: status is " + trade.getStatus() +
                ". Trade must be CONFIRMED first."
            );
        }
        // Continuer le settlement...
    }
}
```

---

### Q8. Modification d'un IRS — qui est autorisé ?

> Une contrepartie demande de modifier un IRS : Notional 100M€ → 120M€.
> Trader ? Middle Office ? Batch ? Application ?

#### Réponse directe

| Acteur | Autorisé ? | Raison |
|--------|-----------|--------|
| **Trader** | Partiellement | Il peut *initier* la modification, mais elle doit être validée |
| **Middle Office** | Oui — validation | Il confirme les nouveaux termes, met à jour le statut |
| **Batch** | Non | Un batch ne décide pas d'une modification économique |
| **Application seule** | Non | L'application exécute, mais ne décide pas sans approbation humaine |

#### Processus standard pour un Amendment

```
1. DEMANDE
   La contrepartie (Goldman) envoie une demande d'amendment
   → Reçue par le Middle Office ou via MarkitWire/DTCC

2. VALIDATION MÉTIER
   Le Trader confirme (changement économique significatif)
   Le Middle Office valide les nouveaux termes

3. BOOKING DE L'AMENDMENT
   L'application crée un événement d'amendment lié au trade original
   Le Notional passe de 100M€ à 120M€
   Un événement TradeAmendedEvent est publié

4. CONFIRMATION BILATÉRALE
   Les deux parties re-confirment les nouveaux termes (FpML / MarkitWire)

5. RECALCUL
   Pricing Engine → recalcule la valeur du trade avec le nouveau Notional
   Risk Engine    → met à jour les sensibilités (+20M€ de Notional)
   EMIR Reporting → l'amendment doit être déclaré au Trade Repository
```

#### Trace d'audit obligatoire

```java
public class TradeAmendment {
    private final String tradeId;
    private final BigDecimal previousNotional;   // 100_000_000
    private final BigDecimal newNotional;         // 120_000_000
    private final String requestedBy;            // LEI de Goldman Sachs
    private final String approvedBy;             // "middle_office_user_123"
    private final Instant amendmentTimestamp;
    private final String emirReportId;           // référence EMIR obligatoire
}
```

---

### Q9. Architecture microservices — découpage par domaine

> Comment découperiez-vous les domaines suivants ? Trade, Position, Market Data, Pricing, Settlement, Counterparty. Feriez-vous plusieurs microservices ? Pourquoi ?

#### Découpage proposé

Oui, **plusieurs microservices**, découpés par **bounded context** (DDD), car chaque domaine a des contraintes non-fonctionnelles radicalement différentes.

| Service | Responsabilité | Contrainte principale |
|---------|---------------|----------------------|
| **Trade Service** | Booking, cycle de vie, state machine | Cohérence ACID, auditabilité, immutabilité |
| **Position Service** | Agrégation des positions en temps réel | Faible latence, haute disponibilité |
| **Market Data Service** | Distribution des prix, courbes, fixings | Débit élevé, pub/sub, cache distribué |
| **Pricing Engine** | Calcul MTM, sensibilités, NPV | CPU-intensif, scalabilité horizontale |
| **Settlement Service** | Orchestration settlements, SWIFT | Idempotence absolue, auditabilité totale |
| **Counterparty Service** | Référentiel LEI, KYC | Référentiel maître, cohérence forte |

#### Architecture cible

```
                    ┌────────────────┐
                    │  API Gateway   │
                    └───────┬────────┘
                            │
          ┌─────────────────┼──────────────────┐
          │                 │                  │
   ┌──────▼──────┐  ┌──────▼──────┐  ┌────────▼───────┐
   │Trade Service│  │Position Svc │  │Counterparty Svc│
   │  (booking)  │  │ (agrégation)│  │  (référentiel) │
   └──────┬──────┘  └──────▲──────┘  └────────────────┘
          │                │
          │   Kafka Topic: trades.booked
          └────────────────┤
                           │
    ┌──────────────────────┼────────────────────┐
    │                      │                    │
┌───▼──────┐    ┌──────────▼──────┐  ┌─────────▼──────┐
│ Pricing  │    │  Risk Engine    │  │Settlement Svc  │
│ Engine   │    │  (VaR, DV01)    │  │  (SWIFT, EOD)  │
└───▲──────┘    └─────────────────┘  └────────────────┘
    │
┌───┴──────────┐
│Market Data   │
│Service       │
│(Redis cache) │
└──────────────┘
```

#### Pourquoi cette séparation ?

**1. Contraintes non-fonctionnelles incompatibles :**
- **Market Data Service** : diffuse des milliers de prix/seconde → Redis, pas de DB transactionnelle
- **Trade Service** : garantit la consistance ACID → PostgreSQL, transactions strictes
- **Pricing Engine** : CPU-intensif → scalabilité horizontale sur K8s

**2. Déploiement indépendant :**
- On scale le Pricing Engine avant la clôture EOD sans toucher le Settlement
- Un bug en Settlement n'impacte pas le Pricing

**3. Ownership d'équipe clair :**
- Chaque équipe (FO dev, MO dev, BO dev) possède son service, son schéma, son déploiement

#### Ce qu'on évite

```
❌ Un seul monolithe "Finance"
   → trop couplé, impossible à scaler sélectivement

❌ Un microservice par endpoint REST
   → trop granulaire, overhead réseau, complexité opérationnelle inutile

✅ Un service par bounded context métier
   → cohérent DDD, aligné sur l'organisation des équipes
```

---

### Q10. PnL en baisse de 2M€ — que vérifier en premier ?

> Un trader vous appelle : "Mon PnL a perdu 2 millions d'euros depuis ce matin."
> En tant que développeur Java, quelles vérifications effectueriez-vous en premier ?

On ne saute pas directement au code — on **qualifie le problème** d'abord.

#### Étape 1 : Comprendre le périmètre

```
- Depuis quand exactement ? (depuis l'ouverture ? depuis minuit ?)
- Sur quel book / quels instruments ?
- Est-ce que d'autres traders observent le même problème ?
- Le marché a-t-il bougé (EURIBOR, taux, prix d'actions...) ?
```

Si le marché a bougé de façon cohérente avec la perte → c'est peut-être du **PnL réel**, pas un bug.

#### Étape 2 : Vérifier les données de marché

Les prix de marché utilisés pour le MTM sont-ils corrects ?

```sql
SELECT instrument, price, source, timestamp
FROM market_data
WHERE book_date = CURRENT_DATE
  AND instrument IN (SELECT instrument FROM position WHERE book = 'BOOK_XYZ')
ORDER BY timestamp DESC;
```

Un seul prix d'obligation erroné peut générer un faux PnL de plusieurs millions.

#### Étape 3 : Vérifier les trades du jour

- Y a-t-il des **nouveaux trades** bookés ce matin ?
- Un trade a-t-il été **annulé ou amendé** ?
- Y a-t-il des **trades en doublon** (booking deux fois) ?

```sql
SELECT trade_id, instrument, direction, quantity, price, status, created_at
FROM trade
WHERE book = 'BOOK_XYZ'
  AND created_at >= CURRENT_DATE
ORDER BY created_at;
```

#### Étape 4 : Vérifier le batch PnL d'hier soir

- Le batch EOD **s'est-il terminé correctement** ?
- A-t-il utilisé les **bons prix de clôture** ?
- Y a-t-il des **exceptions silencieuses** dans les logs ?

```sql
SELECT job_name, status, start_time, end_time, exit_message
FROM batch_job_execution
WHERE job_name = 'pnl-calculation-job'
ORDER BY start_time DESC
LIMIT 5;
```

#### Étape 5 : Vérifier les positions

La position du trader dans le système correspond-elle à ce qu'il déclare détenir ? Chercher des trades non reflétés (problème de consommation d'événements Kafka).

#### Étape 6 : Escalader immédiatement

- Informer le **TL technique** — pas de correction silencieuse
- Informer le **Middle Office / Risk** — ce n'est pas un problème que le dev résout seul
- Ne **pas modifier les données** en production sans processus formel

---

## Round 3 — Java Design + Finance

---

### Q11. Settlement batch interrompu — éviter le double settlement

> Le batch de settlement s'interrompt au milieu du traitement. Comment garantir qu'un trade ne soit jamais réglé deux fois ?

Un double settlement signifie que la banque a envoyé deux paiements (ou deux livraisons) pour le même trade. C'est un problème d'**idempotence critique**.

#### Couche 1 : UPDATE atomique conditionnel sur le statut

```java
@Service
@Transactional
public class SettlementBatchProcessor {

    public void processTrade(String tradeId) {
        // Ne réussit QUE si status = PENDING_SETTLEMENT
        // Atomique : deux threads ne peuvent pas passer cette barrière en même temps
        int updated = tradeRepository.updateStatusIfPending(
            tradeId,
            TradeStatus.PENDING_SETTLEMENT,
            TradeStatus.SETTLEMENT_IN_PROGRESS
        );

        if (updated == 0) {
            log.warn("Trade {} already processed or not in PENDING state, skipping", tradeId);
            return; // skip silencieux et sûr
        }

        try {
            SwiftMessage msg = generateSwiftMT541(tradeId);
            swiftSender.send(msg);
            tradeRepository.updateStatus(tradeId, TradeStatus.SETTLED);
        } catch (Exception e) {
            tradeRepository.updateStatus(tradeId, TradeStatus.SETTLEMENT_FAILED);
            log.error("Settlement failed for trade {}", tradeId, e);
        }
    }
}
```

```java
// Repository
@Modifying
@Query("""
    UPDATE Trade t SET t.status = :newStatus
    WHERE t.tradeId = :id AND t.status = :expectedStatus
    """)
int updateStatusIfPending(
    @Param("id") String tradeId,
    @Param("expectedStatus") TradeStatus expectedStatus,
    @Param("newStatus") TradeStatus newStatus
);
```

#### Couche 2 : Contrainte UNIQUE en base

```sql
CREATE TABLE settlement_execution (
    trade_id     VARCHAR(50) PRIMARY KEY,  -- une seule entrée par trade
    swift_ref    VARCHAR(100),
    executed_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    status       VARCHAR(20)
);

-- INSERT ... ON CONFLICT DO NOTHING (PostgreSQL)
-- Si le trade_id existe déjà → INSERT ignoré, pas d'erreur, pas de doublon
```

#### Couche 3 : Spring Batch restart natif

Spring Batch conserve l'état en base (`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`). En cas de restart, il reprend là où il s'est arrêté.

```java
@Bean
public Step settlementStep() {
    return stepBuilderFactory.get("settlement")
        .<Trade, SettlementResult>chunk(100)
        .reader(pendingSettlementReader())
        .processor(settlementProcessor())
        .writer(settlementWriter())
        .faultTolerant()
        .skip(SettlementAlreadyProcessedException.class)
        .skipLimit(10)
        .build();
}
```

#### Résumé des garanties

```
Garantie 1 : UPDATE atomique conditionnel (PENDING → IN_PROGRESS)
Garantie 2 : Contrainte UNIQUE sur trade_id dans settlement_execution
Garantie 3 : Spring Batch restart (reprend après interruption)
Garantie 4 : SWIFT Sender Reference unique par trade (détection doublon réseau)
```

---

### Q12. Valoriser 2 millions d'IRS chaque nuit

> Vous devez valoriser 2 millions d'IRS chaque nuit. Batch ? Multi-thread ? Partitionnement ? Kafka ?

#### Contraintes du problème

- **Volume** : 2 millions d'IRS
- **Délai** : fenêtre de nuit (objectif : 3-4 heures maximum)
- **Précision** : calcul BigDecimal, cohérence des résultats
- **Reprise** : si ça plante à mi-chemin, on doit pouvoir reprendre

#### Architecture : Spring Batch avec partitionnement parallèle

```java
@Configuration
public class IrsValuationBatchConfig {

    @Bean
    public Job irsValuationJob() {
        return jobBuilderFactory.get("irsValuation")
            .start(partitionedValuationStep())
            .build();
    }

    @Bean
    public Step partitionedValuationStep() {
        return stepBuilderFactory.get("irsValuation")
            .partitioner("valuationStep", irsPartitioner())
            .step(valuationStep())
            .gridSize(20)           // 20 partitions → 20 threads parallèles
            .taskExecutor(asyncTaskExecutor())
            .build();
    }

    @Bean
    public Partitioner irsPartitioner() {
        return gridSize -> {
            Map<String, ExecutionContext> partitions = new HashMap<>();
            long total = irsRepository.count(); // 2 000 000
            long size  = total / gridSize;      // 100 000 par partition

            for (int i = 0; i < gridSize; i++) {
                ExecutionContext ctx = new ExecutionContext();
                ctx.putLong("offset", (long) i * size);
                ctx.putLong("limit", size);
                partitions.put("partition" + i, ctx);
            }
            return partitions;
        };
    }

    @Bean
    public Step valuationStep() {
        return stepBuilderFactory.get("valuationStep")
            .<IrsTrade, ValuationResult>chunk(500)
            .reader(irsReader(null, null))          // offset/limit injectés par partition
            .processor(irsValuationProcessor())      // pricing IRS
            .writer(valuationResultWriter())
            .build();
    }
}
```

#### Pré-chargement du cache de Market Data (critique pour la performance)

```java
@Component
public class IrsValuationProcessor implements ItemProcessor<IrsTrade, ValuationResult> {

    // Chargé UNE FOIS avant le batch → pas de requête DB par trade
    private Map<String, YieldCurve> yieldCurves;

    @BeforeStep
    public void preloadMarketData(StepExecution stepExecution) {
        this.yieldCurves = marketDataService.loadAllYieldCurves(LocalDate.now());
        log.info("Loaded {} yield curves for pricing", yieldCurves.size());
    }

    @Override
    public ValuationResult process(IrsTrade irs) {
        YieldCurve curve = yieldCurves.get(irs.getFloatingIndex()); // lookup mémoire
        BigDecimal npv  = pricingEngine.calculateNPV(irs, curve);
        BigDecimal dv01 = pricingEngine.calculateDV01(irs, curve);
        return new ValuationResult(irs.getTradeId(), npv, dv01, LocalDate.now());
    }
}
```

#### Écriture en batch (INSERT groupés)

```java
@Component
public class ValuationResultWriter implements ItemWriter<ValuationResult> {

    @Override
    public void write(List<? extends ValuationResult> results) {
        jdbcTemplate.batchUpdate(
            "INSERT INTO valuation_result (trade_id, npv, dv01, valuation_date) VALUES (?, ?, ?, ?)",
            results, 500,
            (ps, r) -> {
                ps.setString(1, r.getTradeId());
                ps.setBigDecimal(2, r.getNpv());
                ps.setBigDecimal(3, r.getDv01());
                ps.setDate(4, Date.valueOf(r.getValuationDate()));
            }
        );
    }
}
```

#### Quand utiliser Kafka ?

Kafka est pertinent si le moteur de pricing est **externe** (cluster de pricing distribué) :

```
Spring Batch → Kafka "irs-to-price" → Pricing Cluster (N workers)
                                    ↓
Spring Batch ← Kafka "irs-priced"  ← résultats enrichis
```

Si le calcul est in-process en Java, Spring Batch + multi-threading suffit sans Kafka.

#### Estimation de performance

```
2 000 000 IRS ÷ 20 partitions = 100 000 par partition
Temps de pricing par IRS      ≈ 1ms (IRS vanilla)
100 000 × 1ms                 = 100 secondes par thread

→ Durée totale ≈ 2-3 minutes pour 20 partitions parallèles
→ Pour des IRS exotiques (5ms chacun) → ~10-15 minutes → acceptable
```

---

### Q13. Nouvelle courbe Euribor — recalcul total ou partiel ?

> Une nouvelle courbe de taux (Euribor) vient d'être reçue. Recalculez-vous toutes les positions ? Ou uniquement certaines ? Pourquoi ?

#### Réponse : recalcul partiel et ciblé

On **ne recalcule pas toutes les positions**. On recalcule uniquement celles qui dépendent de la courbe EURIBOR reçue.

#### Pourquoi pas tout recalculer ?

- Une position en **actions** ne dépend pas de l'EURIBOR → repricing inutile
- Une position en **or** ou **FX** n'est pas sensible à cette courbe
- Recalcul total = gaspillage massif de CPU et risque de dépasser la fenêtre de calcul

#### Quelles positions sont impactées ?

```
EURIBOR curve update → impacte :
✅ IRS avec jambe EURIBOR
✅ FRN (Floating Rate Notes) indexées EURIBOR
✅ Caps / Floors / Swaptions basés sur EURIBOR
✅ Cross-Currency Swaps avec jambe EUR

❌ Actions              → non impactées
❌ FX spot              → non impacté directement
❌ Gold                 → non impacté
❌ IRS basés sur SOFR   → autre courbe, non impacté
```

#### Architecture : index de sensibilité + repricing ciblé

```java
@Service
public class MarketDataChangeHandler {

    @EventListener
    public void onYieldCurveUpdate(YieldCurveUpdatedEvent event) {
        String curveId = event.getCurveId(); // "EURIBOR_3M"

        // Trouver UNIQUEMENT les positions sensibles à cette courbe
        List<String> affected = sensitivityIndex.findByCurve(curveId);

        if (affected.isEmpty()) {
            log.info("No positions affected by curve {}", curveId);
            return;
        }

        log.info("Repricing {} positions affected by {}", affected.size(), curveId);
        repricingService.repriceAsync(affected, event.getNewCurve());
    }
}

// L'index est maintenu à jour à chaque nouveau trade bookké
@Entity
public class PositionSensitivity {
    private String positionId;
    private String curveId;    // "EURIBOR_3M", "SOFR", "OAT_10Y"...
    private BigDecimal dv01;   // sensibilité à 1bp de cette courbe
}
```

#### En temps réel vs EOD

- **Intraday** : repricing partiel dès la réception du nouveau fixing, pour les positions actives des traders
- **EOD** : recalcul complet avec les prix de clôture officiels pour le PnL réglementaire

---

### Q14. Concurrence entre saisie de trade et mise à jour de position

> Pendant qu'un trader saisit un nouveau trade, le moteur de calcul met également à jour la Position. Quels problèmes de concurrence peuvent apparaître ? Comment les éviter ?

#### Problèmes de concurrence identifiés

**Problème 1 : Lost Update (mise à jour perdue)**

```
Thread 1 (Trade)   :  lire position (qty = 1000)
Thread 2 (Moteur)  :  lire position (qty = 1000)
Thread 1 (Trade)   :  calculer 1000 + 500 = 1500, écrire 1500
Thread 2 (Moteur)  :  calculer 1000 - 200 = 800,  écrire 800  ← écrase le +500 !

Résultat : position = 800 au lieu de 1300 → trade perdu dans la position
```

**Problème 2 : Dirty Read**

Le moteur lit une position en cours de mise à jour → il calcule le PnL sur une valeur incohérente.

**Problème 3 : Phantom Read**

Pendant que le moteur itère toutes les positions, de nouveaux trades créent de nouvelles positions → le calcul est incomplet.

#### Solutions

**Solution 1 : UPDATE atomique SQL (recommandé)**

```java
// Jamais de read-modify-write applicatif
// L'UPDATE est atomique côté base de données
@Modifying
@Query("UPDATE Position p SET p.quantity = p.quantity + :delta WHERE p.positionId = :id")
int incrementQuantity(@Param("id") String positionId, @Param("delta") BigDecimal delta);
```

**Solution 2 : Optimistic Locking JPA**

```java
@Entity
public class Position {
    @Version
    private Long version; // si deux threads lisent la même version, le 2ème échoue

    private BigDecimal quantity;
}

@Service
public class PositionUpdateService {

    @Retryable(value = OptimisticLockException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 50))
    @Transactional
    public void updatePosition(String positionId, BigDecimal delta) {
        Position p = positionRepository.findById(positionId).orElseThrow();
        p.setQuantity(p.getQuantity().add(delta));
        positionRepository.save(p);
        // Si version changée entre findById et save → OptimisticLockException → retry
    }
}
```

**Solution 3 : Architecture événementielle avec Kafka (scalable)**

```java
// Tous les trades du même instrument vont dans la même partition Kafka
kafkaTemplate.send(
    "position-updates",
    trade.getIsin(),              // la clé = l'ISIN
    new PositionDeltaEvent(trade)
);

// Le consumer traite séquentiellement par partition → 1 thread par ISIN → pas de concurrence
@KafkaListener(topics = "position-updates", groupId = "position-service")
public void onPositionDelta(PositionDeltaEvent event) {
    positionService.applyDelta(event.getIsin(), event.getDeltaQty());
}
```

#### Recommandation

```
En base de données seule    → UPDATE atomique SQL (option 1) — simple et efficace
Environnement distribué     → Kafka par partition ISIN (option 3) — scalable
Transactions mixtes complex → Optimistic locking + retry (option 2) — filet de sécurité
```

---

### Q15. Message FpML reçu deux fois — éviter les doublons

> La contrepartie envoie deux fois le même message FpML. Comment éviter la création de deux trades identiques ?

**FpML** (Financial products Markup Language) est le format XML standard pour les dérivés OTC. La contrepartie peut renvoyer le même message en cas de timeout réseau.

#### Solution 1 : Idempotency Key via UTI

Chaque message FpML contient un **UTI** (Unique Trade Identifier — obligatoire sous EMIR).

```java
@Service
public class FpmlIngestionService {

    @Transactional
    public Trade ingestFpml(FpmlMessage message) {
        String uti = message.getUti();

        // Vérifier si ce trade a déjà été traité
        Optional<Trade> existing = tradeRepository.findByUti(uti);
        if (existing.isPresent()) {
            log.warn("Duplicate FpML for UTI {}. Returning existing trade.", uti);
            return existing.get(); // idempotent : même résultat, pas de doublon
        }

        Trade trade = fpmlMapper.toTrade(message);
        return tradeRepository.save(trade);
    }
}
```

#### Solution 2 : Contrainte UNIQUE en base

```sql
ALTER TABLE trade ADD CONSTRAINT uk_trade_uti UNIQUE (uti);

-- PostgreSQL : INSERT ... ON CONFLICT DO NOTHING
-- Oracle : MERGE ... WHEN NOT MATCHED THEN INSERT
```

```java
public Trade ingestFpml(FpmlMessage message) {
    Trade trade = fpmlMapper.toTrade(message);
    try {
        return tradeRepository.save(trade);
    } catch (DataIntegrityViolationException e) {
        // Doublon détecté par la contrainte → récupérer et retourner
        return tradeRepository.findByUti(trade.getUti()).orElseThrow();
    }
}
```

#### Solution 3 : Deduplication Redis côté messaging

```java
@Component
public class FpmlDeduplicator {

    @Autowired
    private RedisTemplate<String, String> redis;

    public boolean isAlreadyProcessed(String uti) {
        String key = "fpml:processed:" + uti;
        // SETNX atomique : Set if Not eXists
        Boolean isNew = redis.opsForValue()
            .setIfAbsent(key, "1", Duration.ofDays(7));
        return Boolean.FALSE.equals(isNew); // true = doublon détecté
    }
}
```

#### Principe d'idempotence

```
Si le message FpML est reçu deux fois :
→ Le système retourne le MÊME résultat (le trade existant)
→ Pas d'erreur, pas de doublon
→ La contrepartie peut renvoyer sans risque
C'est la définition de l'idempotence : f(f(x)) = f(x)
```

---

## Questions Complémentaires

---

### Q16a. Différence entre Order, Trade et Position

| Concept | Définition | Exemple concret |
|---------|-----------|-----------------|
| **Order** | Une **intention** de trading, pas encore exécutée | "Je veux acheter 1 000 actions BNP à 58€ maximum" |
| **Trade** | Un **accord conclu** entre deux parties. L'ordre a été exécuté. | "J'ai acheté 1 000 actions BNP à 58,20€ auprès de Goldman" |
| **Position** | L'**état agrégé** de tous les trades sur un instrument | "Je détiens +1 200 actions BNP (après plusieurs trades)" |

#### Cycle de vie

```
Order (intention)
  ↓
  Exécution (matching en bourse ou négociation OTC)
  ↓
Trade (fait accompli, bookké avec TradeId immuable)
  ↓
  Agrégation avec les autres trades du même instrument
  ↓
Position (état courant, mis à jour en temps réel)
```

#### Modélisation Java

```java
// Un order peut générer 0, 1 ou plusieurs trades (partial fills)
public class Order {
    private OrderId orderId;
    private Instrument instrument;
    private Side side;           // BUY / SELL
    private Quantity quantity;
    private Price limitPrice;
    private OrderStatus status;  // NEW, PARTIAL, FILLED, CANCELLED
    private List<Trade> fills;   // les trades générés par cet ordre
}

// Un trade est immuable une fois bookké
public class Trade {
    private final TradeId tradeId;      // UUID, jamais modifié
    private final Instrument instrument;
    private final Side direction;
    private final Quantity quantity;
    private final Price executedPrice;  // prix réel d'exécution
    private final Counterparty counterparty;
    private TradeStatus status;         // seul le statut peut évoluer
}

// La position est une vue agrégée
public class Position {
    private Instrument instrument;
    private BigDecimal netQuantity;    // somme algébrique de tous les trades
    private BigDecimal avgCostPrice;   // prix moyen d'acquisition
    private BigDecimal marketValue;    // valeur MTM actuelle
    private BigDecimal unrealizedPnl;  // PnL non réalisé
}
```

---

### Q16b. Cycle de vie d'un IRS

Un **Interest Rate Swap (IRS)** a une durée de vie longue (1 à 30 ans) avec des événements récurrents tout au long de sa vie.

```
┌──────────────────────────────────────────────────────────────┐
│                    CYCLE DE VIE D'UN IRS                     │
└──────────────────────────────────────────────────────────────┘

1. ORIGINATION (Trade Date)
   ├── Négociation OTC entre les deux contreparties
   ├── Trade capturé via FpML ou interface de trading
   └── Statut : NEW

2. CONFIRMATION
   ├── Échange des termes via MarkitWire / DTCC
   ├── Les deux parties confirment tous les termes :
   │   Trade Date, Effective Date, Maturity, Notional,
   │   Fixed Rate, Floating Index, Payment Freq, Reset Freq
   └── Statut : CONFIRMED

3. CLEARING (si applicable — obligatoire sous EMIR pour IRS standardisés)
   ├── Soumission à la CCP (LCH SwapClear ou Eurex)
   ├── La CCP s'interpose comme contrepartie centrale
   ├── Appels de marge initiaux (Initial Margin déposée)
   └── Statut : CLEARED

4. VALORISATION QUOTIDIENNE (pendant toute la durée de vie)
   ├── Pricing Engine calcule le NPV chaque soir
   ├── Basé sur la courbe EURIBOR actuelle vs taux fixe à la conclusion
   ├── Génère les appels de marge quotidiens (Variation Margin)
   └── Alimente le calcul PnL du trader

5. RESET (à chaque Reset Date)
   ├── L'EURIBOR est observé et fixé pour la prochaine période
   ├── Le montant du prochain cash flow variable est calculé
   └── Exemple : EURIBOR 3M fixé à 3,85%

6. PAYMENT (à chaque Payment Date)
   ├── Paiement net entre les deux parties
   ├── Fixed Payer paie : Fixed Rate × Notional × DCF
   │   Float Receiver reçoit : EURIBOR × Notional × DCF
   │   Net : seule la différence est échangée (via SWIFT)
   └── Statut du cash flow : SETTLED

7. EVENTS DE CYCLE DE VIE (si nécessaires)
   ├── Amendment      : modification de termes (Notional, Rate...)
   ├── Partial Term.  : réduction du Notional
   ├── Assignment     : transfert à un tiers (novation)
   └── Compression    : annulation nettée avec d'autres swaps

8. MATURITY (à Maturity Date)
   ├── Dernier paiement net échangé
   ├── Le swap est clôturé
   └── Statut : MATURED / TERMINATED
```

#### Modélisation Java des événements de cycle de vie

```java
public sealed interface IrsLifecycleEvent permits
    IrsConfirmedEvent,
    IrsClearedEvent,
    IrsResetEvent,
    IrsPaymentEvent,
    IrsAmendmentEvent,
    IrsTerminationEvent,
    IrsMaturityEvent {

    String getTradeId();
    LocalDate getEventDate();
    IrsEventType getEventType();
}
```

---

### Q16c. Rôle de la confirmation d'un trade OTC

La confirmation d'un trade OTC est le processus par lequel **les deux contreparties valident que leurs enregistrements sont identiques**.

#### Pourquoi c'est nécessaire ?

Contrairement aux trades boursiers (confirmés automatiquement par la bourse), les trades **OTC** sont négociés directement. Chaque partie enregistre le trade dans son propre système.

**Risque sans confirmation** : les deux systèmes peuvent avoir des termes légèrement différents (erreur de saisie, malentendu sur le taux, mauvaise devise).

#### Ce que la confirmation vérifie

```
Trader A (BNP) enregistre :
  Achat IRS, Notional 100M EUR, Fixed Rate 3,25%, EURIBOR 6M
  Effective Date 2024-10-20, Maturity Date 2029-10-20
  BNP paie fixe

Trader B (Deutsche) enregistre :
  Vente IRS, Notional 100M EUR, Fixed Rate 3,25%, EURIBOR 6M
  Effective Date 2024-10-20, Maturity Date 2029-10-20
  Deutsche reçoit fixe

→ Termes identiques → CONFIRMED ✅
```

#### Comment ça fonctionne en pratique

- **MarkitWire** (pour les IRS) : plateforme qui reçoit les trades des deux parties et les matche automatiquement
- **DTCC** : pour d'autres types de dérivés OTC
- **FpML** : format d'échange des termes entre systèmes

#### Conséquences d'un trade en "broke"

Si les termes ne matchent pas → trade **"in broke"** (unmatched) :
- Le Middle Office doit investiguer et résoudre le désaccord avec la contrepartie
- Sous **EMIR**, un trade non confirmé dans les délais réglementaires génère des pénalités
- Le **settlement ne peut pas avoir lieu** tant que le trade n'est pas confirmé
- Pénalités **CSDR** si le fail dépasse les délais

---

### Q16d. Garantir qu'un settlement ne soit jamais exécuté deux fois

Defense in depth : plusieurs couches de protection indépendantes.

#### Couche 1 : State machine avec UPDATE conditionnel atomique

```java
@Modifying
@Query("""
    UPDATE Trade t
    SET t.status = 'SETTLEMENT_IN_PROGRESS'
    WHERE t.tradeId = :id
      AND t.status = 'PENDING_SETTLEMENT'
    """)
int claimForSettlement(@Param("id") String tradeId);

// Si updated == 0 → déjà traité ou pas en PENDING → skip en sécurité
```

#### Couche 2 : Contrainte UNIQUE en base

```sql
CREATE TABLE settlement_execution (
    trade_id     VARCHAR(50) PRIMARY KEY,  -- une seule entrée par trade GARANTI
    swift_ref    VARCHAR(100) UNIQUE,      -- référence SWIFT unique
    executed_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    status       VARCHAR(20)
);
-- INSERT ... ON CONFLICT DO NOTHING → doublon ignoré sans erreur
```

#### Couche 3 : Audit trail immuable

```java
// Chaque action de settlement loguée, jamais effacée
@Entity
@Immutable
public class SettlementAuditLog {
    private final String tradeId;
    private final SettlementAction action; // INITIATED, SENT, CONFIRMED, FAILED
    private final Instant timestamp;
    private final String operator;
}
```

#### Couche 4 : Réconciliation post-settlement

```java
@Scheduled(cron = "0 0 6 * * MON-FRI")
public void reconcileSettlements() {
    // Comparer confirmations SWIFT reçues vs instructions envoyées
    // Détecter doublons, manquants, écarts de montant
    List<ReconciliationBreak> breaks = reconciliationService.reconcile();
    if (!breaks.isEmpty()) {
        alertService.alertMiddleOffice(breaks);
    }
}
```

---

### Q16e. Recalcul de PnL quand les Market Data changent

#### Architecture événementielle réactive

```
Market Data Source (Reuters / Bloomberg)
           │
           ▼
  Market Data Service (normalisation, validation)
           │
           ▼ MarketDataUpdatedEvent (Kafka)
  ┌────────┴──────────┐
  │                   │
  ▼                   ▼
Sensitivity Index  Event Bus
(positions         (Kafka topic)
 impactées ?)          │
  │                    ▼
  └──────────► Repricing Service (calcul MTM)
                       │
                       ▼ PnlUpdatedEvent
              PnL Store / Reporting
```

#### Implémentation du handler

```java
@Service
public class MarketDataChangeHandler {

    @KafkaListener(topics = "market-data-updates")
    public void onMarketDataUpdate(MarketDataUpdatedEvent event) {
        // 1. Trouver les positions sensibles à CE changement uniquement
        List<String> affected = sensitivityIndex
            .findPositionsAffectedBy(event.getCurveId());

        if (affected.isEmpty()) return;

        log.info("{} positions to reprice for curve {}", affected.size(), event.getCurveId());

        // 2. Recalculer le MTM en parallèle
        affected.parallelStream().forEach(positionId -> {
            BigDecimal newMtm      = repricingService.reprice(positionId, event.getNewData());
            BigDecimal previousMtm = pnlRepository.getLatestMtm(positionId);
            BigDecimal pnlImpact   = newMtm.subtract(previousMtm);

            // 3. Enregistrer la variation de PnL avec la cause
            pnlRepository.recordPnlUpdate(positionId, newMtm, pnlImpact,
                event.getCurveId(), Instant.now());
        });
    }
}
```

#### PnL Explain — décomposition par facteur de risque

```java
// Expliquer POURQUOI le PnL a changé → clé pour les traders et les régulateurs
public class PnlExplain {
    private BigDecimal pnlFromEuribor;    // variation due aux taux EURIBOR
    private BigDecimal pnlFromCredit;     // variation due aux credit spreads
    private BigDecimal pnlFromFx;         // variation due aux FX
    private BigDecimal pnlFromEquity;     // variation due aux actions
    private BigDecimal pnlFromNewTrades;  // nouveaux trades / annulations du jour
    private BigDecimal pnlFromTheta;      // passage du temps (options)
    private BigDecimal unexplainedPnl;    // écart non attribué → à investiguer
}
```

#### Throttling — éviter les orages d'événements

```java
// Si 1000 prix changent en 1 seconde → on ne lance pas 1000 repricings
// On accumule sur une fenêtre courte et on reprend en batch
@Scheduled(fixedDelay = 500) // toutes les 500ms
public void flushPendingUpdates() {
    if (pendingUpdates.isEmpty()) return;
    Map<String, MarketDataUpdatedEvent> batch = new HashMap<>(pendingUpdates);
    pendingUpdates.clear();
    repricingService.repriceBatch(batch);
}
```

---

## Résumé des patterns clés

| Pattern | Utilisation | Questions |
|---------|-------------|-----------|
| **State Machine** | Cycle de vie des trades (transitions autorisées) | Q7, Q16b |
| **Optimistic Locking** | Mises à jour concurrentes sans deadlock | Q14 |
| **UPDATE atomique conditionnel** | Éviter double processing en batch | Q11, Q16d |
| **Idempotency Key (UTI)** | Double booking, double settlement | Q11, Q15, Q16d |
| **Outbox Pattern** | Cohérence DB + Kafka sans 2PC | Q11 |
| **Event-Driven / Kafka** | Réaction aux market data, découplage | Q5, Q14, Q16e |
| **Spring Batch Partitioning** | Batch de valorisation massivement parallèle | Q12 |
| **Sensitivity Index** | Repricing ciblé — pas tout recalculer | Q13, Q16e |
| **PnL Explain** | Décomposer le PnL par facteur de risque | Q10, Q16e |
| **Audit Trail immuable** | Réglementation, traçabilité totale | Q8, Q16d |
