# Entretien Java + Finance — Réponses détaillées

---

## Table des matières

### Round 2 — Questions Métier
- [Q1. Que signifie couvrir un risque de taux ?](#q1-que-signifie-couvrir-un-risque-de-taux-)
- [Q2. À quoi servent Payment Frequency et Reset Frequency ?](#q2-à-quoi-servent-payment-frequency-et-reset-frequency-)
- [Q3. Quelles peuvent être les causes métier d'un settlement failed ?](#q3-quelles-peuvent-être-les-causes-métier-dun-settlement-failed-)
- [Q4. Pourquoi le Risk Management s'intéresse davantage à la Position qu'aux Trades ?](#q4-pourquoi-le-risk-management-sintéresse-davantage-à-la-position-quaux-trades-)
- [Q5. Que se passe-t-il lorsqu'une nouvelle valeur d'Euribor est reçue ?](#q5-que-se-passe-t-il-lorsquune-nouvelle-valeur-deuribor-est-reçue-)
- [Q6. Quel est le rôle de Trade Capture, Pricing Engine et Settlement Engine ?](#q6-quel-est-le-rôle-de-trade-capture-pricing-engine-et-settlement-engine-)
- [Q7. Faut-il autoriser le settlement d'un trade non confirmé ?](#q7-faut-il-autoriser-le-settlement-dun-trade-non-confirmé-)
- [Q8. Qui peut modifier le notional d'un IRS de 100 M€ à 120 M€ ?](#q8-qui-peut-modifier-le-notional-dun-irs-de-100-m-à-120-m-)
- [Q9. Comment découper Trade, Position, Market Data, Pricing, Settlement et Counterparty ?](#q9-comment-découper-trade-position-market-data-pricing-settlement-et-counterparty-)
- [Q10. Le trader dit : « Mon PnL a perdu 2 millions depuis ce matin. » Que vérifier ?](#q10-le-trader-dit--mon-pnl-a-perdu-2-millions-depuis-ce-matin--que-vérifier-)

### Round 3 — Java Design + Finance
- [Q11. Comment éviter qu'un settlement soit exécuté deux fois ?](#q11-comment-éviter-quun-settlement-soit-exécuté-deux-fois-)
- [Q12. Comment valoriser 2 millions d'IRS chaque nuit ?](#q12-comment-valoriser-2-millions-dirs-chaque-nuit-)
- [Q13. Faut-il recalculer toutes les positions lorsqu'une courbe Euribor change ?](#q13-faut-il-recalculer-toutes-les-positions-lorsquune-courbe-euribor-change-)
- [Q14. Comment gérer la concurrence entre booking de trade et mise à jour de position ?](#q14-comment-gérer-la-concurrence-entre-booking-de-trade-et-mise-à-jour-de-position-)
- [Q15. Comment éviter la création de deux trades à partir du même FpML ?](#q15-comment-éviter-la-création-de-deux-trades-à-partir-du-même-fpml-)

### Cinq questions fréquemment posées
- [Q16. Quelle est la différence entre un Order, un Trade et une Position ?](#q16-quelle-est-la-différence-entre-un-order-un-trade-et-une-position-)
- [Q17. Pouvez-vous expliquer le cycle de vie d'un IRS ?](#q17-pouvez-vous-expliquer-le-cycle-de-vie-dun-irs-)
- [Q18. À quoi sert la confirmation d'un trade OTC ?](#q18-à-quoi-sert-la-confirmation-dun-trade-otc-)
- [Q19. Comment garantir qu'un settlement ne soit jamais exécuté deux fois ?](#q19-comment-garantir-quun-settlement-ne-soit-jamais-exécuté-deux-fois-)
- [Q20. Comment concevoir un recalcul du PnL lorsque les Market Data changent ?](#q20-comment-concevoir-un-recalcul-du-pnl-lorsque-les-market-data-changent-)

---

## Round 2 — Questions Métier

---

### Q1. Que signifie couvrir un risque de taux ?

Couvrir un risque de taux signifie réduire ou neutraliser l'impact d'une variation des taux d'intérêt sur le coût de financement d'une entreprise.

Par exemple, une entreprise a contracté un prêt à taux variable :

```
Euribor + 1 %
```

Si l'Euribor augmente, le coût du prêt augmente également.

L'entreprise peut alors conclure un **Interest Rate Swap** dans lequel elle :

- paie un taux fixe ;
- reçoit l'Euribor.

Le flux Euribor reçu dans le swap compense l'Euribor payé sur le prêt. Le coût final devient donc approximativement :

```
taux fixe du swap + marge bancaire
```

L'entreprise ne fait pas cela pour spéculer, mais pour rendre ses charges financières prévisibles. Elle accepte de ne pas profiter d'une éventuelle baisse des taux en échange d'une protection contre leur hausse.

---

### Q2. À quoi servent Payment Frequency et Reset Frequency ?

La **Payment Frequency** indique la fréquence à laquelle les flux financiers sont payés. Par exemple :

- tous les trois mois ;
- tous les six mois ;
- une fois par an.

La **Reset Frequency** indique la fréquence à laquelle le taux variable est redéterminé. Dans un IRS indexé sur l'Euribor 6 mois, le taux variable est généralement fixé pour une période de six mois.

Les deux fréquences peuvent être identiques, mais ce n'est pas obligatoire. Par exemple :

- reset trimestriel ;
- paiement semestriel.

Dans ce cas, deux périodes de taux peuvent être agrégées avant le paiement.

Pour une application Java, ces deux informations sont importantes pour générer :

- les fixing dates ;
- les accrual periods ;
- les payment dates ;
- les cash flows futurs.

---

### Q3. Quelles peuvent être les causes métier d'un settlement failed ?

Un échec de settlement ne signifie pas nécessairement qu'il existe un bug Java.

Les causes métier possibles sont notamment :

- la contrepartie ne dispose pas des fonds nécessaires ;
- le vendeur ne dispose pas des titres à livrer ;
- les instructions de règlement sont absentes ou incorrectes ;
- le compte bancaire ou le compte titres est invalide ;
- les deux contreparties n'ont pas les mêmes données de trade ;
- la devise est incorrecte ;
- la settlement date est erronée ;
- le trade n'est pas encore confirmé ;
- un calendrier de jours fériés a été mal appliqué ;
- le montant calculé est contesté ;
- le cut-off de paiement a été dépassé ;
- la contrepartie ou le système externe a rejeté l'instruction.

En tant que développeur, je commencerais par distinguer :

- un rejet fonctionnel ;
- un problème de données ;
- un problème d'intégration ;
- un problème technique.

---

### Q4. Pourquoi le Risk Management s'intéresse davantage à la Position qu'aux Trades ?

Un trade représente une transaction individuelle. Une position représente l'exposition nette actuelle résultant de l'ensemble des trades.

Par exemple :

- achat de 100 actions BNP ;
- achat de 30 actions BNP ;
- vente de 40 actions BNP ;
- vente de 10 actions BNP.

Il existe quatre trades, mais la position finale est :

```
+80 actions BNP
```

Le Risk Management cherche principalement à connaître l'exposition globale :

- combien l'établissement peut perdre si le marché baisse ;
- quelle est l'exposition à une devise ;
- quelle est l'exposition à une courbe de taux ;
- quelle est l'exposition à une contrepartie ;
- quelles limites ont été consommées.

Les trades sont nécessaires pour expliquer et reconstruire la position, mais le risque est généralement calculé à partir de la position agrégée.

---

### Q5. Que se passe-t-il lorsqu'une nouvelle valeur d'Euribor est reçue ?

L'OMS n'est généralement pas le système principal concerné, sauf s'il affiche des informations liées au pricing ou aux positions.

Les systèmes principalement impactés sont :

#### Pricing Engine

Il peut recalculer la valeur actuelle des instruments dépendant de l'Euribor. Par exemple :

- IRS ;
- FRN ;
- produits de taux ;
- certaines obligations ;
- dérivés de taux.

#### Risk Engine

La variation de taux modifie :

- la valeur des positions ;
- la sensibilité ;
- le PnL ;
- l'exposition.

#### Settlement Engine

Il n'est pas nécessairement déclenché immédiatement. Il intervient si la nouvelle valeur correspond à un fixing utilisé pour calculer un prochain cash flow ou si une payment date approche.

#### Market Data System

Il doit :

- recevoir la donnée ;
- la valider ;
- la versionner ;
- la diffuser ;
- conserver sa provenance et son timestamp.

---

### Q6. Quel est le rôle de Trade Capture, Pricing Engine et Settlement Engine ?

#### Trade Capture

Le **Trade Capture** enregistre les caractéristiques contractuelles du trade :

- produit ;
- contrepartie ;
- notional ;
- devise ;
- taux ;
- dates ;
- book ;
- trader ;
- sens payeur ou receveur.

#### Pricing Engine

Le **Pricing Engine** calcule la valeur économique du trade à partir :

- des données du contrat ;
- des données de marché ;
- des modèles de valorisation.

Il peut produire :

- la valeur actuelle ;
- le PnL ;
- les sensibilités ;
- les cash flows actualisés.

#### Settlement Engine

Le **Settlement Engine** détermine les montants réellement dus à une date donnée et génère les instructions de paiement ou de livraison.

Il ne transfère généralement pas lui-même l'argent. Il transmet une instruction à un système de paiement, un custodian, une CCP ou une infrastructure de marché.

---

### Q7. Faut-il autoriser le settlement d'un trade non confirmé ?

En règle générale, **non**.

Un trade non confirmé signifie que les deux contreparties ne se sont pas encore accordées formellement sur les termes du contrat. Le risque est de payer sur la base de données contestées.

Par exemple, les deux parties peuvent différer sur :

- le notional ;
- le taux fixe ;
- la devise ;
- la payment date ;
- le sens payeur ou receveur.

Le comportement normal serait de bloquer le settlement et de placer le trade dans une **exception queue**.

Cependant, certaines organisations peuvent autoriser une dérogation contrôlée selon :

- le type de produit ;
- le montant ;
- la criticité ;
- le niveau d'autorisation ;
- les règles internes.

La décision doit être métier et auditable, pas codée arbitrairement dans l'application.

---

### Q8. Qui peut modifier le notional d'un IRS de 100 M€ à 120 M€ ?

Une telle modification ne doit **jamais** être effectuée automatiquement par un batch. Elle représente une modification économique du contrat.

Selon l'organisation, le trader peut initier la demande, mais elle doit généralement suivre un workflow contrôlé :

1. demande de modification ;
2. validation par les équipes autorisées ;
3. accord de la contrepartie ;
4. nouvelle confirmation ;
5. recalcul du pricing et du risk ;
6. audit complet.

Dans certains cas, le trade original est annulé et remplacé par un nouveau trade. Dans d'autres cas, une **amendment version** est créée.

Le système doit conserver :

- l'ancienne version ;
- la nouvelle version ;
- l'auteur ;
- la date ;
- la raison ;
- les validations ;
- la référence de confirmation.

---

### Q9. Comment découper Trade, Position, Market Data, Pricing, Settlement et Counterparty ?

Je ne créerais pas automatiquement un microservice par nom métier. Je définirais d'abord les responsabilités et les contraintes de cohérence.

#### Trade Service

Responsable de :

- la création ;
- la modification contrôlée ;
- l'annulation ;
- le versioning ;
- le lifecycle du trade.

#### Position Service

Responsable de :

- l'agrégation des trades ou executions ;
- la position courante ;
- les snapshots ;
- la reconstruction.

#### Market Data Service

Responsable de :

- l'ingestion ;
- la validation ;
- la diffusion ;
- le versioning ;
- la provenance des données de marché.

#### Pricing Service

Responsable de :

- la valorisation ;
- le choix du modèle ;
- les paramètres de pricing ;
- la production du PnL et des sensibilités.

#### Settlement Service

Responsable de :

- l'identification des cash flows dus ;
- le netting ;
- la génération d'instructions ;
- le suivi des statuts ;
- la gestion des échecs.

#### Counterparty Service

Responsable de :

- l'identité de la contrepartie ;
- ses comptes ;
- ses instructions de règlement ;
- ses limites ;
- ses données réglementaires.

Je pourrais séparer ces domaines en services différents si :

- ils ont des cycles de vie distincts ;
- ils doivent scaler différemment ;
- ils appartiennent à des équipes différentes ;
- ils ont des exigences de disponibilité différentes.

Mais j'éviterais un découpage excessif créant trop de transactions distribuées.

---

### Q10. Le trader dit : « Mon PnL a perdu 2 millions depuis ce matin. » Que vérifier ?

Je suivrais un ordre de diagnostic.

#### 1. Market Data

Vérifier :

- si une courbe a changé ;
- si un prix est anormal ;
- si une donnée est stale ;
- si une devise a évolué ;
- si la mauvaise source a été utilisée ;
- si un point de courbe manque.

#### 2. Position

Vérifier :

- si un nouveau trade a été booké ;
- si un trade a été annulé ;
- si une position a été dupliquée ;
- si une execution a été traitée deux fois ;
- si un trade a été affecté au mauvais book.

#### 3. Pricing

Vérifier :

- si le bon modèle est utilisé ;
- si les paramètres ont changé ;
- si une nouvelle version a été déployée ;
- si une calibration a échoué ;
- si une convention de calcul a été modifiée.

#### 4. PnL decomposition

Séparer :

- le PnL lié au marché ;
- le PnL lié aux nouveaux trades ;
- le PnL lié au passage du temps ;
- le PnL lié aux corrections ;
- le PnL FX.

#### 5. Data lineage

Comparer le calcul actuel au calcul précédent :

- même trade version ;
- mêmes market data ;
- même modèle ;
- même timestamp ;
- même configuration.

---

## Round 3 — Java Design + Finance

---

### Q11. Comment éviter qu'un settlement soit exécuté deux fois ?

Le point principal est l'**idempotence**.

Chaque obligation de settlement doit avoir une clé métier unique. Par exemple :

- trade ID ;
- cash flow ID ;
- payment date ;
- currency ;
- payer ;
- receiver.

Avant d'émettre une instruction, le système doit vérifier si une instruction existe déjà.

Techniquement, j'utiliserais :

- une contrainte unique en base ;
- un statut persistant ;
- un identifiant d'idempotence ;
- une transaction locale ;
- un outbox pattern pour publier l'événement ;
- des consumers idempotents.

Exemple de statuts :

```
CREATED → READY → SENT → ACKNOWLEDGED → SETTLED
                                       → FAILED
```

Si le serveur tombe après l'envoi mais avant la mise à jour locale, le système doit pouvoir réconcilier l'état avec le système externe avant de renvoyer. Il ne faut pas supposer qu'un simple retry est sans danger.

---

### Q12. Comment valoriser 2 millions d'IRS chaque nuit ?

Je concevrais un traitement **partitionné et parallélisé**.

#### Étapes

1. sélectionner les trades éligibles ;
2. les partitionner ;
3. charger les market data nécessaires ;
4. lancer les calculs en parallèle ;
5. persister les résultats ;
6. contrôler la complétude ;
7. publier un statut de fin.

#### Partitionnement possible

- par book ;
- par devise ;
- par maturité ;
- par produit ;
- par hash du trade ID.

#### Architecture

Un scheduler crée un pricing run. Il produit des tâches de pricing. Des workers consomment ces tâches et calculent les résultats.

Kafka peut être utilisé pour distribuer le travail, mais ce n'est pas obligatoire. Spring Batch avec partitionnement peut également convenir.

Les points critiques sont :

- reproductibilité ;
- version des market data ;
- version du modèle ;
- retry ciblé ;
- idempotence ;
- traçabilité ;
- contrôle du nombre de trades traités.

---

### Q13. Faut-il recalculer toutes les positions lorsqu'une courbe Euribor change ?

Non, pas nécessairement.

Il faut recalculer uniquement les trades dont la valorisation dépend de la donnée modifiée. Par exemple :

- IRS en EUR ;
- produits indexés sur Euribor ;
- positions utilisant la même discount curve ;
- instruments utilisant une courbe dérivée de cette donnée.

Il faut donc maintenir des dépendances entre :

- instrument ;
- indice ;
- courbe ;
- modèle ;
- market data.

Une architecture event-driven peut publier un événement :

```
EURIBOR_CURVE_UPDATED
```

Le Pricing Engine détermine ensuite les trades impactés.

Pour un petit système, un recalcul global peut être acceptable. Pour des millions de trades, un recalcul ciblé est préférable.

---

### Q14. Comment gérer la concurrence entre booking de trade et mise à jour de position ?

Le principal risque est de **perdre une mise à jour**.

Exemple :

- la position vaut 100 ;
- deux événements ajoutent simultanément +20 et -10 ;
- les deux lisent 100 ;
- l'un écrit 120 ;
- l'autre écrit 90.

Le résultat correct devrait être 110.

#### Solutions possibles

**Optimistic locking**

Ajouter un champ `version` et rejouer la transaction en cas de conflit.

**Atomic database update**

Faire directement :

```sql
UPDATE position
SET quantity = quantity + :delta
WHERE ...
```

**Partitionnement par clé**

Tous les événements concernant le même instrument, book ou position key sont traités séquentiellement.

**Kafka partition**

Utiliser une clé comme :

```
bookId + instrumentId
```

Les événements d'une même position arrivent alors dans la même partition et sont traités dans l'ordre.

Le choix dépend du volume et du besoin de cohérence.

---

### Q15. Comment éviter la création de deux trades à partir du même FpML ?

Il faut utiliser une **clé d'idempotence**. Par exemple :

- message ID externe ;
- trade reference ;
- counterparty trade ID ;
- UTI, Unique Transaction Identifier ;
- combinaison de champs métier.

Le système stocke chaque message déjà traité. Lorsqu'un message arrive :

1. vérifier son identifiant ;
2. s'il existe déjà, retourner le résultat précédent ;
3. sinon, traiter le message ;
4. persister le message et le trade dans la même unité logique.

Une contrainte unique en base est indispensable. Il ne faut pas se contenter d'un simple contrôle en mémoire.

---

## Cinq questions fréquemment posées

---

### Q16. Quelle est la différence entre un Order, un Trade et une Position ?

Un **Order** représente une intention d'acheter ou de vendre. Exemple :

```
acheter 100 actions BNP à 60 € maximum
```

Un **Trade** représente une transaction réellement exécutée. Si seulement 40 actions sont exécutées, le trade porte sur 40 actions.

Une **Position** représente l'exposition nette résultant des trades. Exemple :

- achat 100 ;
- vente 30 ;
- achat 20.

Position finale : **+90**.

En résumé :

| Concept | Définition |
|---------|-----------|
| **Order** | intention |
| **Trade** | exécution réelle |
| **Position** | exposition actuelle |

---

### Q17. Pouvez-vous expliquer le cycle de vie d'un IRS ?

Un cycle de vie simplifié serait :

#### Execution

Deux contreparties s'accordent sur les termes du swap.

#### Trade Capture

Le trade est enregistré dans le système interne.

#### Validation

Le système contrôle :

- les données obligatoires ;
- la contrepartie ;
- les limites ;
- les dates ;
- les conventions.

#### Confirmation

Les deux parties confirment qu'elles ont les mêmes termes contractuels.

#### Pricing et Risk

Le trade est valorisé régulièrement à partir des données de marché.

#### Fixing

À certaines dates, le taux variable est fixé.

#### Cash Flow Generation

Les montants à payer sont calculés.

#### Settlement

Le montant net est payé à la date prévue.

#### Amendments éventuels

Le trade peut être modifié, novaté, compressé ou résilié.

#### Maturity

À l'échéance finale, le dernier cash flow est réglé et le trade est clôturé.

---

### Q18. À quoi sert la confirmation d'un trade OTC ?

Dans une transaction OTC, il n'existe pas nécessairement d'exchange central qui garantit que les deux parties ont exactement les mêmes données. Chaque contrepartie possède son propre système.

La confirmation permet de vérifier que les deux versions correspondent. Les champs comparés peuvent inclure :

- produit ;
- notional ;
- taux ;
- devise ;
- dates ;
- payer ;
- receiver ;
- index ;
- conventions.

Si les données correspondent, le trade devient **confirmed**. Sinon, une exception est créée et les équipes opérationnelles doivent résoudre le mismatch.

---

### Q19. Comment garantir qu'un settlement ne soit jamais exécuté deux fois ?

Je mettrais en place plusieurs niveaux de protection :

- clé métier unique ;
- contrainte unique en base ;
- état persistant du settlement ;
- idempotency key envoyée au système externe ;
- outbox pattern ;
- consumer idempotent ;
- reconciliation avec le système de paiement ;
- audit trail.

La difficulté principale se situe dans le cas suivant :

1. l'instruction est envoyée ;
2. le système externe la reçoit ;
3. l'application tombe avant d'enregistrer le succès.

Au redémarrage, il ne faut **pas renvoyer aveuglément**. Il faut interroger ou réconcilier le statut externe.

---

### Q20. Comment concevoir un recalcul du PnL lorsque les Market Data changent ?

Je partirais d'un événement de market data. Exemple :

```
EURIBOR_CURVE_UPDATED
```

Cet événement contiendrait :

- le type de donnée ;
- son identifiant ;
- sa version ;
- son timestamp ;
- sa source.

Le système identifierait ensuite les trades impactés. Il créerait un **pricing run versionné**.

Chaque résultat de PnL conserverait :

- le trade ID ;
- la trade version ;
- la market data version ;
- la pricing model version ;
- le timestamp ;
- le résultat.

Le traitement pourrait être parallélisé. Il faudrait également :

- éviter les recalculs inutiles ;
- permettre le retry ;
- comparer l'ancien et le nouveau PnL ;
- produire une decomposition ;
- garantir qu'un même run ne soit pas exécuté plusieurs fois ;
- rendre le calcul reproductible pour l'audit.

Le point essentiel est qu'un chiffre de PnL doit toujours pouvoir être expliqué par :

> **une position précise + une version de market data + une version de modèle.**
